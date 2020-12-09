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
package io.curity.dynamoDBDataAccessProvider

import io.curity.dynamoDBDataAccessProvider.token.FilterParser
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import se.curity.identityserver.sdk.attribute.AccountAttributes
import se.curity.identityserver.sdk.attribute.scim.v2.ResourceAttributes
import se.curity.identityserver.sdk.attribute.scim.v2.extensions.LinkedAccount
import se.curity.identityserver.sdk.data.query.ResourceQuery
import se.curity.identityserver.sdk.data.query.ResourceQueryResult
import se.curity.identityserver.sdk.data.update.AttributeUpdate
import se.curity.identityserver.sdk.datasource.UserAccountDataAccessProvider
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.ScanRequest
import software.amazon.awssdk.services.dynamodb.model.ScanResponse
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest
import java.util.Collections

class DynamoDBDataAccessProviderUserAccountDataAccessProvider(private val dynamoDBClient: DynamoDBClient): UserAccountDataAccessProvider
{
    override fun getByUserName(userName: String, attributesEnumeration: ResourceQuery.AttributesEnumeration?): ResourceAttributes<*>?
    {
        logger.debug("Received request to get account by username : {}", userName)

        val requestBuilder = GetItemRequest.builder()
                .tableName(tableName)
                .key(userName.toKey("userName"))

        if (attributesEnumeration != null && attributesEnumeration.attributes.isNotEmpty()) {
            requestBuilder.attributesToGet(attributesEnumeration.attributes)
        }

        val request = requestBuilder.build()

        val response = dynamoDBClient.getItem(request)

        return if (response.hasItem()) {
            toAccountAttributes(response.item())
        } else {
            null
        }
    }

    override fun getByEmail(email: String, attributesEnumeration: ResourceQuery.AttributesEnumeration?): ResourceAttributes<*>?
    {
        logger.debug("Received request to get account by email : {}", email)

        return retrieveByQuery("email-index", "email = :email", ":email", email, attributesEnumeration)
    }

    override fun getByPhone(phone: String, attributesEnumeration: ResourceQuery.AttributesEnumeration?): ResourceAttributes<*>?
    {
        logger.debug("Received request to get account by phone number : {}", phone)

        return retrieveByQuery("phone-index", "phone = :phone", ":phone", phone, attributesEnumeration)
    }

    private fun retrieveByQuery(indexName: String, keyCondition: String, attributeKey: String, attributeValue: String, attributesEnumeration: ResourceQuery.AttributesEnumeration?): ResourceAttributes<*>? {
        val requestBuilder = QueryRequest.builder()
                .tableName(tableName)
                .indexName(indexName)
                .keyConditionExpression(keyCondition)
                .expressionAttributeValues(mapOf(Pair(attributeKey, AttributeValue.builder().s(attributeValue).build())))
                .limit(1)

        if (attributesEnumeration != null && attributesEnumeration.attributes.isNotEmpty()) {
            requestBuilder.attributesToGet(attributesEnumeration.attributes)
        }

        val request = requestBuilder.build()

        val response = dynamoDBClient.query(request)

        return if (response.hasItems() && response.items().isNotEmpty()) {
            toAccountAttributes(response.items().first())
        } else {
            null
        }
    }

    override fun create(accountAttributes: AccountAttributes): AccountAttributes
    {

        logger.debug("Received request to create account with data : {}", accountAttributes)

        val item = accountAttributes.toItem()
        val request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build()

        dynamoDBClient.putItem(request)

        return toAccountAttributes(item)
    }

    override fun update(accountAttributes: AccountAttributes,
                                        attributesEnumeration: ResourceQuery.AttributesEnumeration): ResourceAttributes<*>?
    {
        logger.debug("Received request to update account with data : {}", accountAttributes)

        // For now only update "active" status

        val username = accountAttributes["userName"].attributeValue.toString()

        val request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(username.toKey("userName"))
                .expressionAttributeValues(mapOf<String, AttributeValue>(Pair(":active", AttributeValue.builder().bool(accountAttributes["active"].value as Boolean).build())))
                .updateExpression("SET active = :active")
                .build()

        dynamoDBClient.updateItem(request)

        return getByUserName(username, attributesEnumeration)
    }

    override fun update(accountId: String, map: Map<String, Any>,
                                        attributesEnumeration: ResourceQuery.AttributesEnumeration): ResourceAttributes<*>?
    {
        logger.debug("Received request to update account with id:{} and data : {}", accountId, map)

        // For now only update active

        val request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(accountId.toKey("userName"))
                .expressionAttributeValues(mapOf<String, AttributeValue>(Pair(":active", AttributeValue.builder().bool(map["active"] as Boolean).build())))
                .updateExpression("SET active = :active")
                .build()

        dynamoDBClient.updateItem(request)

        return getByUserName(accountId, attributesEnumeration)
    }

    override fun patch(accountId: String, attributeUpdate: AttributeUpdate,
                                            attributesEnumeration: ResourceQuery.AttributesEnumeration): ResourceAttributes<*>?
    {
        logger.debug("Received patch request with accountId:{} and data : {}", accountId, attributeUpdate)

        // For now only update "active"

        if (attributeUpdate.attributeAdditions.contains("active") || attributeUpdate.attributeReplacements.contains("active")) {

            val active = if (attributeUpdate.attributeAdditions.contains("active"))
                attributeUpdate.attributeAdditions["active"].value as Boolean else
                attributeUpdate.attributeReplacements["active"].value as Boolean

            val request = UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(accountId.toKey("userName"))
                    .expressionAttributeValues(mapOf<String, AttributeValue>(Pair(":active", AttributeValue.builder().bool(active).build())))
                    .updateExpression("SET active = :active")
                    .build()

            dynamoDBClient.updateItem(request)
        } else if (attributeUpdate.attributeDeletions.attributeNamesToDelete.contains("active")) {
            val request = UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(accountId.toKey("userName"))
                    .updateExpression("REMOVE active")
                    .build()

            dynamoDBClient.updateItem(request)
        }

        return getByUserName(accountId, attributesEnumeration)
    }

    override fun link(linkingAccountManager: String, localAccountId: String, foreignDomainName: String, foreignUserName: String)
    {
        /**
         * The account links table has primary key: foreignUserName@foreignDomainName-linkingAccountManager
         * Secondary Index: primary key: foreignId@Domain with sort key linkingAccountManager
         * Attributes: linkingAccountManager, localAccountId
         */
        val item = mutableMapOf<String, AttributeValue>()
        item["localAccountId"] = AttributeValue.builder().s(localAccountId).build()
        item["linkingAccountManager"] = AttributeValue.builder().s(linkingAccountManager).build()
        item["foreignAccountAtDomain"] = AttributeValue.builder().s("$foreignUserName@$foreignDomainName").build()
        item["foreignAccountId"] = AttributeValue.builder().s(foreignUserName).build()
        item["foreignDomainName"] = AttributeValue.builder().s(foreignDomainName).build()
        item["localIdToforeignIdAtdomainForManager"] = AttributeValue.builder().s("$foreignUserName@$foreignDomainName-$linkingAccountManager").build()

        val request = PutItemRequest.builder()
                .tableName(linksTableName)
                .item(item)
                .build()

        dynamoDBClient.putItem(request)
    }

    override fun listLinks(linkingAccountManager: String, localAccountId: String): Collection<LinkedAccount>
    {
        val attributes = mapOf(
                Pair(":manager", AttributeValue.builder().s(linkingAccountManager).build()),
                Pair(":accountId", AttributeValue.builder().s(localAccountId).build())
        )
        val request = QueryRequest.builder()
                .tableName(linksTableName)
                .indexName("list-links-index")
                .keyConditionExpression("linkingAccountManager = :manager AND localAccountId = :accountId")
                .expressionAttributeValues(attributes)
                .build()

        val response = dynamoDBClient.query(request)

        return if (response.hasItems() && response.items().isNotEmpty()) {
            response.items().map { item -> LinkedAccount.of(item["foreignDomainName"]?.s(), item["foreignAccountId"]?.s()) }
        } else {
            emptyList()
        }
    }

    override fun resolveLink(linkingAccountManager: String, foreignDomainName: String, foreignAccountId: String): AccountAttributes?
    {
        val request = GetItemRequest.builder()
                .tableName(linksTableName)
                .key(getLinksKey(foreignAccountId, foreignDomainName, linkingAccountManager))
                .build()

        val response = dynamoDBClient.getItem(request)

        if (!response.hasItem() || response.item().isEmpty()) {
            return null
        }

        val localAccountId = response.item()["localAccountId"]!!.s()

        return getById(localAccountId)
    }

    override fun deleteLink(linkingAccountManager: String, localAccountId: String, foreignDomainName: String, foreignAccountId: String): Boolean
    {
        val request = DeleteItemRequest.builder()
                .tableName(linksTableName)
                .key(getLinksKey(foreignAccountId, foreignDomainName, linkingAccountManager))
                .build()

        val response = dynamoDBClient.deleteItem(request)

        return response.sdkHttpResponse().isSuccessful
    }

    override fun delete(accountId: String)
    {
        logger.debug("Received request to delete account with accountId: {}", accountId)

        val request = DeleteItemRequest.builder()
                .tableName(tableName)
                .key(accountId.toKey("userName"))
                .build()

        dynamoDBClient.deleteItem(request)
    }

    override fun getAll(query: ResourceQuery): ResourceQueryResult
    {
        //TODO should implement pagination and proper handling of DynamoDB pagination

        val requestBuilder = ScanRequest.builder()
                .tableName(tableName)

        if (query.filter != null)
        {
            val parsedFilter = FilterParser(query.filter)

            requestBuilder
                    .filterExpression(parsedFilter.parsedFilter)
                    .expressionAttributeValues(parsedFilter.attributeValues)
            if (parsedFilter.attributesNamesMap.isNotEmpty()) {
                requestBuilder.expressionAttributeNames(parsedFilter.attributesNamesMap)
            }

//            logger.warn("Getting all users with filter: {}", parsedFilter.parsedFilter)
//            logger.warn("Values for parameters: {}", parsedFilter.attributeValues.map { (k,v) -> "$k: ${v.s()}" }.joinToString(", "))
        }

        val response = dynamoDBClient.scan(requestBuilder.build())

//        logger.warn("Found {} items, out of {} items", response.count(), response.scannedCount())

        val results  = mutableListOf<ResourceAttributes<*>>()

        response.items().forEach {item ->
            results.add(toAccountAttributes(item))
        }

        return ResourceQueryResult(results, response.count().toLong(), 0, response.count().toLong())
    }

    override fun getAll(startIndex: Long, count: Long): ResourceQueryResult
    {
        logger.debug("Received request to get all accounts with startIndex :{} and count: {}", startIndex, count)

        // TODO: should implement pagination and take into account DynamoDBs possible pagination

        val request = ScanRequest.builder()
                .tableName(tableName)
                .build()

        val response = dynamoDBClient.scan(request)
        val results  = mutableListOf<ResourceAttributes<*>>()

        response.items().forEach {item ->
            results.add(toAccountAttributes(item))
        }

        return ResourceQueryResult(results, response.count().toLong(), 0, response.count().toLong())
    }

    private fun getLinksKey(foreignAccountId: String, foreignDomainName: String, linkingAccountManager: String): Map<String, AttributeValue> =
            mapOf(Pair("localIdToforeignIdAtdomainForManager", AttributeValue.builder().s("$foreignAccountId@$foreignDomainName-$linkingAccountManager").build()))

    private fun AccountAttributes.toItem(): MutableMap<String, AttributeValue>
    {
        val item = mutableMapOf<String, AttributeValue>()

        if (name != null) {
            item["name"] = AttributeValue.builder().m(
                    mapOf(
                            Pair("givenName", AttributeValue.builder().s(name.givenName).build()),
                            Pair("familyName", AttributeValue.builder().s(name.familyName).build())
                    )
            ).build()
        }

        item["email"] = AttributeValue.builder().s(emails.primary.significantValue).build()

        if (!phoneNumbers.isEmpty) {
            item["phone"] = AttributeValue.builder().s(phoneNumbers.primary.significantValue).build()
        }

        item["userName"] = AttributeValue.builder().s(userName).build()
        item["password"] = AttributeValue.builder().s(password).build()
        item["active"] = AttributeValue.builder().bool(isActive).build()

        return item
    }

    private fun toAccountAttributes(item: Map<String, AttributeValue>): AccountAttributes
    {
        val map = mutableMapOf<String, Any?>()

        map["id"] = item["userName"]?.s()

        item.forEach {(key, value) ->
            when (key) {
                "name" -> {
                    val nameMap = value.m()
                    map["name"] = mapOf(Pair("givenName", nameMap["givenName"]?.s()), Pair("familyName", nameMap["familyName"]?.s())) }
                "active" -> map["active"] = value.bool()
                "email" -> map["emails"] = listOf(mapOf(Pair("value", value.s()), Pair("isPrimary", true)))
                "phone" -> map["phoneNumbers"] = listOf(mapOf(Pair("value", value.s()), Pair("isPrimary", true)))
                else -> map[key] = value.s()
            }
        }

        return AccountAttributes.fromMap(map)
    }

    companion object
    {
        private const val tableName = "curity-accounts"
        private const val linksTableName = "curity-links"
        private val logger: Logger = LoggerFactory.getLogger(DynamoDBDataAccessProviderCredentialDataAccessProvider::class.java)
    }
}
