/*
 *  Copyright 2020 Curity AB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.curity.identityserver.plugin.dynamodb.token

import io.curity.identityserver.plugin.dynamodb.DynamoDBClient
import io.curity.identityserver.plugin.dynamodb.Index
import io.curity.identityserver.plugin.dynamodb.Index2
import io.curity.identityserver.plugin.dynamodb.NumberLongAttribute
import io.curity.identityserver.plugin.dynamodb.SchemaErrorException
import io.curity.identityserver.plugin.dynamodb.StringAttribute
import io.curity.identityserver.plugin.dynamodb.Table
import io.curity.identityserver.plugin.dynamodb.configuration.DynamoDBDataAccessProviderConfiguration
import io.curity.identityserver.plugin.dynamodb.count
import io.curity.identityserver.plugin.dynamodb.intOrThrow
import io.curity.identityserver.plugin.dynamodb.querySequence
import io.curity.identityserver.plugin.dynamodb.scanSequence
import io.curity.identityserver.plugin.dynamodb.toAttributeValue
import org.slf4j.LoggerFactory
import se.curity.identityserver.sdk.attribute.Attributes
import se.curity.identityserver.sdk.attribute.AuthenticationAttributes
import se.curity.identityserver.sdk.data.authorization.Delegation
import se.curity.identityserver.sdk.data.authorization.DelegationConsentResult
import se.curity.identityserver.sdk.data.authorization.DelegationStatus
import se.curity.identityserver.sdk.data.query.ResourceQuery
import se.curity.identityserver.sdk.datasource.DelegationDataAccessProvider
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.ReturnValue
import software.amazon.awssdk.services.dynamodb.model.ScanRequest
import software.amazon.awssdk.services.dynamodb.model.Select
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest

class DynamoDBDelegationDataAccessProvider(
    private val dynamoDBClient: DynamoDBClient,
    configuration: DynamoDBDataAccessProviderConfiguration
) : DelegationDataAccessProvider
{
    private val jsonHandler = configuration.getJsonHandler()

    override fun getById(id: String): Delegation?
    {
        val request = GetItemRequest.builder()
            .tableName(DelegationTable.name)
            .key(mapOf(DelegationTable.id.toNameValuePair(id)))
            .build()

        val response = dynamoDBClient.getItem(request)

        if (!response.hasItem() || response.item().isEmpty())
        {
            return null
        }
        val item = response.item()

        val status =
            DelegationStatus.valueOf(
                DelegationTable.status.fromOpt(item)
                    ?: throw SchemaErrorException(DelegationTable, DelegationTable.status)
            )
        if (status != DelegationStatus.issued)
        {
            return null
        }
        return item.toDelegation()
    }

    override fun getByAuthorizationCodeHash(authorizationCodeHash: String): Delegation?
    {
        val index = DelegationTable.authorizationCodeIndex
        val request = QueryRequest.builder()
            .tableName(DelegationTable.name)
            .indexName(index.name)
            .keyConditionExpression(index.expression)
            .expressionAttributeValues(index.expressionValueMap(authorizationCodeHash))
            .expressionAttributeNames(index.expressionNameMap)
            .build()

        val response = dynamoDBClient.query(request)

        if (!response.hasItems() || response.items().isEmpty())
        {
            return null
        }

        // If multiple entries exist, we use the first one
        return response.items().first().toDelegation()
    }

    override fun create(delegation: Delegation)
    {
        val request = PutItemRequest.builder()
            .tableName(DelegationTable.name)
            .item(delegation.toItem())
            .build()

        dynamoDBClient.putItem(request)
    }

    override fun setStatus(id: String, newStatus: DelegationStatus): Long
    {
        val request = UpdateItemRequest.builder()
            .tableName(DelegationTable.name)
            .key(mapOf(DelegationTable.id.toNameValuePair(id)))
            .updateExpression("SET ${DelegationTable.status.hashName} = ${DelegationTable.status.colonName}")
            .expressionAttributeValues(mapOf(DelegationTable.status.toExpressionNameValuePair(newStatus.toString())))
            .expressionAttributeNames(mapOf(DelegationTable.status.toNamePair()))
            .returnValues(ReturnValue.UPDATED_NEW)
            .build()

        val response = dynamoDBClient.updateItem(request)

        return if (response.hasAttributes() && response.attributes().isNotEmpty())
        {
            1
        } else
        {
            0
        }
    }

    override fun getByOwner(owner: String, startIndex: Long, count: Long): Collection<Delegation>
    {
        val validatedStartIndex = startIndex.intOrThrow("startIndex")
        val validatedCount = count.intOrThrow("count")
        val index = DelegationTable.ownerStatusIndex
        val request = QueryRequest.builder()
            .tableName(DelegationTable.name)
            .indexName(index.name)
            .keyConditionExpression(index.keyConditionExpression)
            .expressionAttributeValues(
                index.expressionValueMap(owner, DelegationStatus.issued.toString())
            )
            .expressionAttributeNames(index.expressionNameMap)
            .limit(count.toInt())
            .build()

        return querySequence(request, dynamoDBClient)
            .drop(validatedStartIndex)
            .map { it.toDelegation() }
            .take(validatedCount)
            .toList()
    }

    override fun getCountByOwner(owner: String): Long
    {
        val index = DelegationTable.ownerStatusIndex
        val request = QueryRequest.builder()
            .tableName(DelegationTable.name)
            .indexName(index.name)
            .keyConditionExpression(index.keyConditionExpression)
            .expressionAttributeValues(
                index.expressionValueMap(owner, DelegationStatus.issued.toString())
            )
            .expressionAttributeNames(index.expressionNameMap)
            .select(Select.COUNT)
            .build()

        return count(request, dynamoDBClient)
    }

    override fun getAllActive(startIndex: Long, count: Long): Collection<Delegation>
    {
        val validatedStartIndex = startIndex.intOrThrow("startIndex")
        val validatedCount = count.intOrThrow("count")

        val request = ScanRequest.builder()
            .tableName(DelegationTable.name)
            .filterExpression("#status = :status")
            .expressionAttributeValues(issuedStatusExpressionAttributeMap)
            .expressionAttributeNames(issuedStatusExpressionAttributeNameMap)
            .build()

        return scanSequence(request, dynamoDBClient)
            .drop(validatedStartIndex)
            .map { it.toDelegation() }
            .take(validatedCount)
            .toList()
    }

    override fun getCountAllActive(): Long
    {
        val request = ScanRequest.builder()
            .tableName(DelegationTable.name)
            .filterExpression("#status = :status")
            .expressionAttributeValues(issuedStatusExpressionAttributeMap)
            .expressionAttributeNames(issuedStatusExpressionAttributeNameMap)
            .select(Select.COUNT)
            .build()

        return count(request, dynamoDBClient)
    }

    override fun getAll(query: ResourceQuery): MutableCollection<out Delegation>
    {
        // TODO implement sorting - can be tricky
        // TODO implement pagination and possible DynamoDB pagination

        val requestBuilder = ScanRequest.builder()
            .tableName(DelegationTable.name)

        if (!query.attributesEnumeration.isNeutral)
        {
            val attributesEnumeration = query.attributesEnumeration

            if (attributesEnumeration is ResourceQuery.Inclusions)
            {
                requestBuilder.projectionExpression(attributesEnumeration.attributes.joinToString(","))
            } else
            {
                // must be exclusions
                requestBuilder.attributesToGet(
                    DelegationTable.possibleAttributes.minus(attributesEnumeration.attributes)
                        .joinToString(","))
            }
        }

        if (query.filter != null)
        {
            val filterParser = DelegationsFilterParser(query.filter)

            logger.warn(
                "Calling getAll with filter: {}, values set: {}. Values: {}",
                filterParser.parsedFilter,
                filterParser.attributeValues.count(),
                filterParser.attributeValues.map { value -> value.value.s() }.joinToString(", ")
            )

            requestBuilder.filterExpression(filterParser.parsedFilter)
            requestBuilder.expressionAttributeValues(filterParser.attributeValues)

            if (filterParser.attributesNamesMap.isNotEmpty())
            {
                requestBuilder.expressionAttributeNames(filterParser.attributesNamesMap)
            }
        }

        val response = dynamoDBClient.scan(requestBuilder.build())

        val result = mutableListOf<Delegation>()

        if (response.hasItems() && response.items().isNotEmpty())
        {
            response.items().forEach { item -> result.add(item.toDelegation()) }
        }

        return result
    }

    private fun Delegation.toItem(): Map<String, AttributeValue>
    {
        val res = mutableMapOf<String, AttributeValue>()
        DelegationTable.version.addTo(res, "6.2")
        DelegationTable.id.addTo(res, id)
        DelegationTable.status.addTo(res, status.name)
        DelegationTable.owner.addTo(res, owner)
        DelegationTable.created.addTo(res, created)
        DelegationTable.expires.addTo(res, expires)
        DelegationTable.clientId.addTo(res, clientId)
        DelegationTable.redirectUri.addToOpt(res, redirectUri)
        DelegationTable.authorizationCodeHash.addToOpt(res, authorizationCodeHash)
        DelegationTable.scope.addTo(res, scope)

        DelegationTable.mtlsClientCertificate.addToOpt(res, mtlsClientCertificate)
        DelegationTable.mtlsClientCertificateDN.addToOpt(res, mtlsClientCertificateDN)
        DelegationTable.mtlsClientCertificateX5TS256.addToOpt(res, mtlsClientCertificateX5TS256)

        DelegationTable.authenticationAttributes.addTo(res, jsonHandler.toJson(authenticationAttributes.asMap()))
        DelegationTable.consentResult.addToOpt(res, consentResult?.asMap()?.let { jsonHandler.toJson(it) })
        DelegationTable.claimMap.addTo(res, jsonHandler.toJson(claimMap))
        DelegationTable.customClaimValues.addTo(res, jsonHandler.toJson(customClaimValues))
        DelegationTable.claims.addTo(res, jsonHandler.toJson(claims))
        return res
    }

    private fun Map<String, AttributeValue>.toDelegation() =
        DynamoDBDelegation(
            version = DelegationTable.version.from(this),
            id = DelegationTable.id.from(this),
            status = DelegationStatus.valueOf(DelegationTable.status.from(this)),
            owner = DelegationTable.owner.from(this),
            created = DelegationTable.created.from(this),
            expires = DelegationTable.expires.from(this),
            clientId = DelegationTable.clientId.from(this),
            redirectUri = DelegationTable.redirectUri.fromOpt(this),
            authorizationCodeHash = DelegationTable.redirectUri.fromOpt(this),
            authenticationAttributes = DelegationTable.authenticationAttributes.from(this).let {
                AuthenticationAttributes.fromAttributes(
                    Attributes.fromMap(
                        jsonHandler.fromJson(it)
                    )
                )
            },
            consentResult = DelegationTable.consentResult.fromOpt(this)?.let {
                DelegationConsentResult.fromMap(
                    jsonHandler.fromJson(it)
                )
            },
            scope = DelegationTable.scope.from(this),
            claimMap = DelegationTable.claimMap.from(this).let { jsonHandler.fromJson(it) },
            customClaimValues = DelegationTable.customClaimValues.from(this).let { jsonHandler.fromJson(it) },
            claims = DelegationTable.claims.from(this).let { jsonHandler.fromJson(it) },
            mtlsClientCertificate = DelegationTable.mtlsClientCertificate.fromOpt(this),
            mtlsClientCertificateDN = DelegationTable.mtlsClientCertificateDN.fromOpt(this),
            mtlsClientCertificateX5TS256 = DelegationTable.mtlsClientCertificateX5TS256.fromOpt(this)
        )

    object DelegationTable : Table("curity-delegations")
    {
        val version = StringAttribute("version")
        val id = StringAttribute("id")
        val status = StringAttribute("status")
        val owner = StringAttribute("owner")
        val authorizationCode = StringAttribute("authorizationCodeHash")

        val created = NumberLongAttribute("created")
        val expires = NumberLongAttribute("expires")

        val scope = StringAttribute("scope")
        val scopeClaims = StringAttribute("scopeClaims")
        val claimMap = StringAttribute("claimMap")
        val clientId = StringAttribute("clientId")
        val redirectUri = StringAttribute("redirectUri")
        val authorizationCodeHash = StringAttribute("authorizationCodeHash")
        val authenticationAttributes = StringAttribute("authenticationAttributes")
        val customClaimValues = StringAttribute("customClaimValues")
        val mtlsClientCertificate = StringAttribute("mtlsClientCertificate")
        val mtlsClientCertificateX5TS256 = StringAttribute("mtlsClientCertificateX5TS256")
        val mtlsClientCertificateDN = StringAttribute("mtlsClientCertificateDN")
        val consentResult = StringAttribute("consentResult")
        val claims = StringAttribute("claims")

        val ownerStatusIndex = Index2("owner-status-index", owner, status)
        val authorizationCodeIndex = Index("authorization-hash-index", authorizationCode)

        val possibleAttributes = listOf(
            id, status, owner, authorizationCode, created, expires, scope, scopeClaims, claimMap,
            clientId, redirectUri, authorizationCodeHash, authenticationAttributes, customClaimValues,
            mtlsClientCertificate, mtlsClientCertificateX5TS256, mtlsClientCertificateDN,
            consentResult, claims
        ).map{
            it.name
        }
    }

    companion object
    {
        private val logger = LoggerFactory.getLogger(DynamoDBDelegationDataAccessProvider::class.java)
        private val issuedStatusExpressionAttribute = Pair(":status", "issued".toAttributeValue())
        private val issuedStatusExpressionAttributeMap = mapOf(issuedStatusExpressionAttribute)
        private val issuedStatusExpressionAttributeName = Pair("#status", "status")
        private val issuedStatusExpressionAttributeNameMap = mapOf(issuedStatusExpressionAttributeName)
    }
}

