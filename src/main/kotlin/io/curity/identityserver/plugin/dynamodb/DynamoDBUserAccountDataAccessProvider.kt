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
package io.curity.identityserver.plugin.dynamodb

import io.curity.identityserver.plugin.dynamodb.configuration.DynamoDBDataAccessProviderConfiguration
import io.curity.identityserver.plugin.dynamodb.query.DynamoDBQueryBuilder
import io.curity.identityserver.plugin.dynamodb.query.Index
import io.curity.identityserver.plugin.dynamodb.query.QueryPlan
import io.curity.identityserver.plugin.dynamodb.query.QueryPlanner
import io.curity.identityserver.plugin.dynamodb.query.TableQueryCapabilities
import io.curity.identityserver.plugin.dynamodb.query.UnsupportedQueryException
import io.curity.identityserver.plugin.dynamodb.query.filterWith
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import se.curity.identityserver.sdk.attribute.AccountAttributes
import se.curity.identityserver.sdk.attribute.Attribute
import se.curity.identityserver.sdk.attribute.Attributes
import se.curity.identityserver.sdk.attribute.AuthenticationAttributes
import se.curity.identityserver.sdk.attribute.ContextAttributes
import se.curity.identityserver.sdk.attribute.SubjectAttributes
import se.curity.identityserver.sdk.attribute.scim.v2.Meta
import se.curity.identityserver.sdk.attribute.scim.v2.ResourceAttributes
import se.curity.identityserver.sdk.attribute.scim.v2.extensions.LinkedAccount
import se.curity.identityserver.sdk.data.query.ResourceQuery
import se.curity.identityserver.sdk.data.query.ResourceQueryResult
import se.curity.identityserver.sdk.data.update.AttributeUpdate
import se.curity.identityserver.sdk.datasource.CredentialDataAccessProvider
import se.curity.identityserver.sdk.datasource.UserAccountDataAccessProvider
import se.curity.identityserver.sdk.errors.ConflictException
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.ScanRequest
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * The users table has three additional uniqueness restrictions, other than the accountId:
 * - The `username` must be unique.
 * - The optional `email` must be unique.
 * - The optional `phone` must be unique.
 * The users table uses the following design to support these additional uniqueness constraints
 * [https://aws.amazon.com/blogs/database/simulating-amazon-dynamodb-unique-constraints-using-transactions/],
 * which is based on adding multiple items per "entity", which in this case is an account.
 *
 * Also, getting by `userName`, `email`, and `phone` number should use reads with strong consistency guarantees.
 * Since strong consistency reads are not supported by Global Secondary Indexes, the design used here replicates all the data
 * into all the items inserted for the same entity.
 * The cost should be similar to having the indexes, because those also replicate the data.
 *
 * Example:
 *
 * | pk                    | version | accountId  | userName | email             | phone     | other attributes
 * | ai#1234               | 12      | 1234       | alice    | alice@example.com | 123456789 | ...      // main item
 * | un#alice              | 12      | 1234       | alice    | alice@example.com | 123456789 | ...      // secondary item
 * | em#alice@example.com  | 12      | 1234       | alice    | alice@example.com | 123456789 | ...      // secondary item
 * | pn#123456789          | 12      | 1234       | alice    | alice@example.com | 123456789 | ...      // secondary item
 *
 * In the following we call "main item" to the item using the `accountId` for the partition key.
 * We call "secondary item" to the items using the `userName`, `email`, and `phone` for the partition key.
 * Both the main item and the secondary items carry all the information.
 * - This means that a `getByEmail` can use the email secondary item without needing an additional index and therefore
 * allowing for strong consistent reads.
 * - However, this also means that any change to an account must be reflected in the main item and all the secondary items
 * (account updates are not frequent).
 *
 * There is a version attribute to support optimistic concurrency when updating or deleting the multiple item from an user
 * on a transaction.
 *
 */
class DynamoDBUserAccountDataAccessProvider(
    private val _dynamoDBClient: DynamoDBClient,
    private val _configuration: DynamoDBDataAccessProviderConfiguration
) : UserAccountDataAccessProvider, CredentialDataAccessProvider {
    private val jsonHandler = _configuration.getJsonHandler()

    override fun getById(
        accountId: String
    ): AccountAttributes? = fromAttributes(getById(accountId, ResourceQuery.Exclusions.none()))

    override fun getById(
        accountId: String,
        attributesEnumeration: ResourceQuery.AttributesEnumeration
    ): ResourceAttributes<*>? {
        _logger.debug(MASK_MARKER, "Received request to get account by ID : {}", accountId)

        return retrieveByUniqueAttribute(AccountsTable.accountId, accountId, attributesEnumeration)
    }

    override fun getByUserName(
        userName: String,
        attributesEnumeration: ResourceQuery.AttributesEnumeration
    ): ResourceAttributes<*>? {
        _logger.debug(MASK_MARKER, "Received request to get account by userName : {}", userName)

        return retrieveByUniqueAttribute(AccountsTable.userName, userName, attributesEnumeration)
    }

    override fun getByEmail(
        email: String,
        attributesEnumeration: ResourceQuery.AttributesEnumeration
    ): ResourceAttributes<*>? {
        _logger.debug(MASK_MARKER, "Received request to get account by email : {}", email)

        return retrieveByUniqueAttribute(AccountsTable.email, email, attributesEnumeration)
    }

    override fun getByPhone(
        phone: String,
        attributesEnumeration: ResourceQuery.AttributesEnumeration
    ): ResourceAttributes<*>? {
        _logger.debug(MASK_MARKER, "Received request to get account by phone number : {}", phone)

        return retrieveByUniqueAttribute(AccountsTable.phone, phone, attributesEnumeration)
    }

    private fun retrieveByUniqueAttribute(
        key: UniqueAttribute<String>,
        keyValue: String,
        attributesEnumeration: ResourceQuery.AttributesEnumeration?
    ): ResourceAttributes<*>? {
        val requestBuilder = GetItemRequest.builder()
            .tableName(AccountsTable.name(_configuration))
            .key(mapOf(AccountsTable.pk.uniqueKeyEntryFor(key, keyValue)))
            .consistentRead(true)

        val request = requestBuilder.build()

        val response = _dynamoDBClient.getItem(request)

        return if (response.hasItem()) {
            response.item().toAccountAttributes(attributesEnumeration)
        } else {
            null
        }
    }

    override fun create(accountAttributes: AccountAttributes): AccountAttributes {
        _logger.debug(MASK_MARKER, "Received request to create account with data : {}", accountAttributes)

        val accountId = UUID.randomUUID().toString()
        val now = Instant.now().epochSecond

        // the commonItem contains the attributes that will be on both the primary and secondary items.
        val commonItem = accountAttributes.toItem(accountId, now, now)

        val userName = accountAttributes.userName
        commonItem.addAttr(AccountsTable.version, 0)

        val transactionItems = mutableListOf<TransactWriteItem>()

        // the item put can only happen if the item does not exist
        val writeConditionExpression = "attribute_not_exists(${AccountsTable.pk.name})"

        // Add main item
        val mainItem = commonItem + mapOf(AccountsTable.pk.uniqueKeyEntryFor(AccountsTable.accountId, accountId))
        transactionItems.add(
            TransactWriteItem.builder()
                .put {
                    it.tableName(AccountsTable.name(_configuration))
                    it.conditionExpression(writeConditionExpression)
                    it.item(mainItem)
                }
                .build()
        )

        // Add secondary item with userName
        val userNameItem = commonItem + mapOf(AccountsTable.pk.uniqueKeyEntryFor(AccountsTable.userName, userName))
        transactionItems.add(
            TransactWriteItem.builder()
                .put {
                    it.tableName(AccountsTable.name(_configuration))
                    it.conditionExpression(writeConditionExpression)
                    it.item(userNameItem)
                }
                .build()
        )

        // Add secondary item with email, if email is present
        commonItem[AccountsTable.email.name]?.also { emailAttr ->
            val emailItem = commonItem + mapOf(AccountsTable.pk.uniqueKeyEntryFor(AccountsTable.email, emailAttr.s()))
            transactionItems.add(
                TransactWriteItem.builder()
                    .put {
                        it.tableName(AccountsTable.name(_configuration))
                        it.conditionExpression(writeConditionExpression)
                        it.item(emailItem)
                    }
                    .build()
            )
        }

        // Add secondary item with phone, if phone is present
        commonItem[AccountsTable.phone.name]?.also { phoneNumberAttr ->
            val phoneItem =
                commonItem + mapOf(AccountsTable.pk.uniqueKeyEntryFor(AccountsTable.phone, phoneNumberAttr.s()))
            transactionItems.add(
                TransactWriteItem.builder()
                    .put {
                        it.tableName(AccountsTable.name(_configuration))
                        it.conditionExpression(writeConditionExpression)
                        it.item(
                            phoneItem
                        )
                    }
                    .build()
            )
        }

        val request = TransactWriteItemsRequest.builder()
            .transactItems(transactionItems)
            .build()

        try {
            _dynamoDBClient.transactionWriteItems(request)
        } catch (ex: Exception) {
            if (ex.isTransactionCancelledDueToConditionFailure()) {
                throw ConflictException(
                    "Unable to create user with username '${accountAttributes.userName}' as uniqueness check failed"
                )
            }
            throw ex
        }

        return commonItem.toAccountAttributes()
    }

    override fun delete(accountId: String) = retry("delete", N_OF_ATTEMPTS) { tryDelete(accountId) }

    private fun tryDelete(accountId: String): TransactionAttemptResult<Unit> {
        _logger.debug(MASK_MARKER, "Received request to delete account with accountId: {}", accountId)

        // Deleting an account requires the deletion of the main item and all the secondary items.
        // A `getItem` is needed to obtain the `userName`, `email`, and `phone` required to compute the
        // secondary item keys.
        val getItemResponse = _dynamoDBClient.getItem(
            GetItemRequest.builder()
                .tableName(AccountsTable.name(_configuration))
                .key(AccountsTable.keyFromAccountId(accountId))
                .build()
        )

        if (!getItemResponse.hasItem() || getItemResponse.item().isEmpty()) {
            return TransactionAttemptResult.Success(Unit)
        }

        val item = getItemResponse.item()
        val version =
            AccountsTable.version.optionalFrom(item)
                ?: throw SchemaErrorException(AccountsTable, AccountsTable.version)
        val userName =
            AccountsTable.userName.optionalFrom(item) ?: throw SchemaErrorException(
                AccountsTable,
                AccountsTable.userName
            )
        // email and phone may not exist
        val email = AccountsTable.email.optionalFrom(item)
        val phone = AccountsTable.phone.optionalFrom(item)

        // Create a transaction with all the items (main and secondary) deletions,
        // conditioned to the version not having changed - optimistic concurrency.
        val transactionItems = mutableListOf<TransactWriteItem>()

        val conditionExpression = versionAndAccountIdConditionExpression(version, accountId)

        transactionItems.add(
            TransactWriteItem.builder()
                .delete {
                    it.tableName(AccountsTable.name(_configuration))
                    it.key(AccountsTable.keyFromAccountId(accountId))
                    it.conditionExpression(conditionExpression)
                }
                .build()
        )
        transactionItems.add(
            TransactWriteItem.builder()
                .delete {
                    it.tableName(AccountsTable.name(_configuration))
                    it.key(mapOf(AccountsTable.pk.uniqueKeyEntryFor(AccountsTable.userName, userName)))
                    it.conditionExpression(conditionExpression)
                }
                .build()
        )
        if (email != null) {
            transactionItems.add(
                TransactWriteItem.builder()
                    .delete {
                        it.tableName(AccountsTable.name(_configuration))
                        it.key(mapOf(AccountsTable.pk.uniqueKeyEntryFor(AccountsTable.email, email)))
                        it.conditionExpression(conditionExpression)
                    }
                    .build()
            )
        }
        if (phone != null) {
            transactionItems.add(
                TransactWriteItem.builder()
                    .delete {
                        it.tableName(AccountsTable.name(_configuration))
                        it.key(mapOf(AccountsTable.pk.uniqueKeyEntryFor(AccountsTable.phone, phone)))
                        it.conditionExpression(conditionExpression)
                    }
                    .build()
            )
        }

        val request = TransactWriteItemsRequest.builder()
            .transactItems(transactionItems)
            .build()

        try {
            _dynamoDBClient.transactionWriteItems(request)
            return TransactionAttemptResult.Success(Unit)
        } catch (ex: Exception) {
            if (ex.isTransactionCancelledDueToConditionFailure()) {
                return TransactionAttemptResult.Failure(
                    ConflictException("Unable to delete user")
                )
            }
            throw ex
        }
    }

    override fun update(
        accountAttributes: AccountAttributes,
        attributesEnumeration: ResourceQuery.AttributesEnumeration
    ): ResourceAttributes<*>? {
        _logger.debug(MASK_MARKER, "Received request to update account with data : {}", accountAttributes)

        val id = accountAttributes.id
        updateAccount(id, accountAttributes)

        // TODO is this really required - the JDBC DAP does it
        return getById(id, attributesEnumeration)
    }

    override fun update(
        accountId: String, map: Map<String, Any>,
        attributesEnumeration: ResourceQuery.AttributesEnumeration
    ): ResourceAttributes<*>? {
        _logger.debug(MASK_MARKER, "Received request to update account with id:{} and data : {}", accountId, map)

        updateAccount(accountId, AccountAttributes.fromMap(map))

        // TODO is this really required - the JDBC DAP does it
        return getById(accountId, attributesEnumeration)
    }

    private fun updateAccount(accountId: String, accountAttributes: AccountAttributes) =
        retry("updateAccount", N_OF_ATTEMPTS) {
            val observedItem = getItemByAccountId(accountId) ?: return@retry TransactionAttemptResult.Success(null)
            tryUpdateAccount(accountId, accountAttributes, observedItem)
        }

    private fun tryUpdateAccount(accountId: String, accountAttributes: AccountAttributes, observedItem: DynamoDBItem)
            : TransactionAttemptResult<Unit> {
        val observedVersion = observedItem.version()
        val newVersion = observedVersion + 1

        val updated = Instant.now().epochSecond

        // The created attribute is not updated
        val created = AccountsTable.created.from(observedItem)
        val commonItem = accountAttributes.toItem(accountId, created, updated)
        commonItem.addAttr(AccountsTable.version, newVersion)
        val maybePassword = AccountsTable.password.optionalFrom(observedItem)

        // The password attribute is not updated
        commonItem.remove(AccountsTable.password.name)
        if (maybePassword != null) {
            commonItem[AccountsTable.password.name] = AccountsTable.password.toAttrValue(maybePassword)
        }

        val updateBuilder = UpdateBuilderWithMultipleUniquenessConstraints(
            _configuration,
            AccountsTable,
            commonItem,
            AccountsTable.pk,
            versionAndAccountIdConditionExpression(observedVersion, accountId)
        )

        updateBuilder.handleUniqueAttribute(
            AccountsTable.accountId,
            accountId,
            accountId
        )

        updateBuilder.handleUniqueAttribute(
            AccountsTable.userName,
            AccountsTable.userName.optionalFrom(observedItem),
            accountAttributes.userName
        )

        updateBuilder.handleUniqueAttribute(
            AccountsTable.email,
            AccountsTable.email.optionalFrom(observedItem),
            accountAttributes.emails.primaryOrFirst?.significantValue
        )

        updateBuilder.handleUniqueAttribute(
            AccountsTable.phone,
            AccountsTable.phone.optionalFrom(observedItem),
            accountAttributes.phoneNumbers.primaryOrFirst?.significantValue
        )

        try {
            _dynamoDBClient.transactionWriteItems(updateBuilder.build())
            return TransactionAttemptResult.Success(Unit)
        } catch (ex: Exception) {
            if (ex.isTransactionCancelledDueToConditionFailure()) {
                return TransactionAttemptResult.Failure(ex)
            }
            throw ex
        }
    }

    override fun patch(
        accountId: String, attributeUpdate: AttributeUpdate,
        attributesEnumeration: ResourceQuery.AttributesEnumeration
    ): ResourceAttributes<*>? {
        retry("updateAccount", N_OF_ATTEMPTS)
        {
            val observedItem = getItemByAccountId(accountId) ?: return@retry TransactionAttemptResult.Success(null)
            val observedAttributes = observedItem.toAccountAttributes()

            if (attributeUpdate.attributeAdditions.contains(AccountAttributes.PASSWORD) ||
                attributeUpdate.attributeReplacements.contains(AccountAttributes.PASSWORD)
            ) {
                _logger.info(
                    "Received an account with a password to update. Cannot update passwords using this method, " +
                            "so the password will be ignored."
                )
            }

            var newAttributes = attributeUpdate.applyOn<Attributes>(observedAttributes)
                .with(Attribute.of(ResourceAttributes.ID, accountId))
                .removeAttribute(ResourceAttributes.META)

            if (newAttributes.contains(AccountAttributes.PASSWORD)) {
                newAttributes = newAttributes.removeAttribute(AccountAttributes.PASSWORD)
            }

            tryUpdateAccount(accountId, fromAttributes(newAttributes), observedItem)
        }

        // TODO is this really required - the JDBC DAP does it
        return getById(accountId, attributesEnumeration)
    }

    override fun updatePassword(accountAttributes: AccountAttributes) {
        val username = accountAttributes.userName
        val password = accountAttributes.password

        _logger.debug(MASK_MARKER, "Received request to update password for username : {}", username)

        retry("updatePassword", N_OF_ATTEMPTS)
        {
            val observedItem = getItemByUsername(username)
            if (observedItem == null) {
                _logger.debug("Unable to update password because there isn't an account with the given userName")
                return@retry TransactionAttemptResult.Success(null)
            }

            val accountId = observedItem.accountId()
            val observedVersion = observedItem.version()
            val newVersion = observedVersion + 1

            val updated = Instant.now().epochSecond
            val commonItem = observedItem + mapOf(
                AccountsTable.updated.toNameValuePair(updated),
                AccountsTable.password.toNameValuePair(password),
                AccountsTable.version.toNameValuePair(newVersion)
            )

            val updateBuilder = UpdateBuilderWithMultipleUniquenessConstraints(
                _configuration,
                AccountsTable,
                commonItem,
                AccountsTable.pk,
                versionAndAccountIdConditionExpression(observedVersion, accountId)
            )

            updateBuilder.handleUniqueAttribute(
                AccountsTable.accountId,
                accountId,
                accountId
            )

            updateBuilder.handleUniqueAttribute(
                AccountsTable.userName,
                username,
                username
            )

            val maybeEmail = AccountsTable.email.optionalFrom(observedItem)

            updateBuilder.handleUniqueAttribute(
                AccountsTable.email,
                maybeEmail,
                maybeEmail
            )

            val maybePhone = AccountsTable.phone.optionalFrom(observedItem)

            updateBuilder.handleUniqueAttribute(
                AccountsTable.phone,
                maybePhone,
                maybePhone
            )

            try {
                _dynamoDBClient.transactionWriteItems(updateBuilder.build())
                return@retry TransactionAttemptResult.Success(Unit)
            } catch (ex: Exception) {
                if (ex.isTransactionCancelledDueToConditionFailure()) {
                    return@retry TransactionAttemptResult.Failure(ex)
                }
                throw ex
            }

        }
    }

    override fun verifyPassword(userName: String, password: String): AuthenticationAttributes? {
        _logger.debug(MASK_MARKER, "Received request to verify password for username : {}", userName)

        val request = GetItemRequest.builder()
            .tableName(AccountsTable.name(_configuration))
            // 'password' is not a DynamoDB reserved word
            .key(
                mapOf(
                    AccountsTable.pk.uniqueKeyEntryFor(
                        AccountsTable.userName, userName
                    )
                )
            )
            .projectionExpression(
                "${AccountsTable.accountId.name}, ${AccountsTable.userName.name}, " +
                        "${AccountsTable.password.name}, ${AccountsTable.active.name}"
            )
            .build()

        val response = _dynamoDBClient.getItem(request)

        if (!response.hasItem()) {
            return null
        }

        val item = response.item()
        val active = AccountsTable.active.optionalFrom(item) ?: false

        if (!active) {
            return null
        }

        // Password is not verified by this DAP, which is aligned with customQueryVerifiesPassword returning false
        return AuthenticationAttributes.of(
            SubjectAttributes.of(
                userName,
                Attributes.of(
                    Attribute.of("password", AccountsTable.password.optionalFrom(item)),
                    Attribute.of("accountId", AccountsTable.accountId.optionalFrom(item)),
                    Attribute.of("userName", AccountsTable.userName.optionalFrom(item))
                )
            ),
            ContextAttributes.empty()
        )
    }

    override fun customQueryVerifiesPassword(): Boolean {
        return false
    }

    private fun getItemByAccountId(accountId: String): DynamoDBItem? {
        val requestBuilder = GetItemRequest.builder()
            .tableName(AccountsTable.name(_configuration))
            .key(AccountsTable.keyFromAccountId(accountId))
            .consistentRead(true)

        val request = requestBuilder.build()

        val response = _dynamoDBClient.getItem(request)
        return if (response.hasItem()) response.item() else null
    }

    private fun getItemByUsername(userName: String): DynamoDBItem? {
        val requestBuilder = GetItemRequest.builder()
            .tableName(AccountsTable.name(_configuration))
            .key(AccountsTable.keyFromUserName(userName))
            .consistentRead(true)

        val request = requestBuilder.build()

        val response = _dynamoDBClient.getItem(request)
        return if (response.hasItem()) response.item() else null
    }

    override fun link(
        linkingAccountManager: String,
        localAccountId: String,
        foreignDomainName: String,
        foreignUserName: String
    ) {
        val request = PutItemRequest.builder()
            .tableName(LinksTable.name(_configuration))
            .item(LinksTable.createItem(linkingAccountManager, localAccountId, foreignDomainName, foreignUserName))
            .build()

        _dynamoDBClient.putItem(request)
    }

    override fun listLinks(linkingAccountManager: String, localAccountId: String): Collection<LinkedAccount> {
        val request = QueryRequest.builder()
            .tableName(LinksTable.name(_configuration))
            .indexName(LinksTable.listLinksIndex.name)
            .keyConditionExpression(LinksTable.listLinksIndex.keyConditionExpression)
            .expressionAttributeValues(
                LinksTable.listLinksIndex.expressionValueMap(
                    localAccountId,
                    linkingAccountManager
                )
            )
            .expressionAttributeNames(LinksTable.listLinksIndex.expressionNameMap)
            .build()

        return querySequence(request, _dynamoDBClient)
            .map { item ->
                LinkedAccount.of(
                    LinksTable.linkedAccountDomainName.from(item),
                    LinksTable.linkedAccountId.from(item),
                    NO_LINK_DESCRIPTION,
                    LinksTable.created.optionalFrom(item).toIsoInstantString()
                )
            }
            .toList()
    }

    override fun resolveLink(
        linkingAccountManager: String,
        foreignDomainName: String,
        foreignAccountId: String
    ): AccountAttributes? {
        val request = GetItemRequest.builder()
            .tableName(LinksTable.name(_configuration))
            .key(mapOf(LinksTable.pk.toNameValuePair(foreignAccountId, foreignDomainName)))
            .consistentRead(true)
            .build()

        val response = _dynamoDBClient.getItem(request)

        if (!response.hasItem() || response.item().isEmpty()) {
            return null
        }

        val item = response.item()

        val itemAccountManager = LinksTable.linkingAccountManager.optionalFrom(item)
            ?: throw SchemaErrorException(LinksTable, LinksTable.linkingAccountManager)

        if (itemAccountManager != linkingAccountManager) {
            return null
        }

        val localAccountId = LinksTable.localAccountId.optionalFrom(item)
            ?: throw SchemaErrorException(LinksTable, LinksTable.localAccountId)

        return getById(localAccountId)
    }

    override fun deleteLink(
        linkingAccountManager: String,
        localAccountId: String,
        foreignDomainName: String,
        foreignAccountId: String
    ): Boolean {
        val request = DeleteItemRequest.builder()
            .tableName(LinksTable.name(_configuration))
            .key(mapOf(LinksTable.pk.toNameValuePair(foreignAccountId, foreignDomainName)))
            .conditionExpression(
                "${LinksTable.localAccountId.name} = ${LinksTable.localAccountId.colonName} AND "
                        + "${LinksTable.linkingAccountManager.name} = ${LinksTable.linkingAccountManager.colonName}"
            )
            .expressionAttributeValues(
                mapOf(
                    LinksTable.localAccountId.toExpressionNameValuePair(localAccountId),
                    LinksTable.linkingAccountManager.toExpressionNameValuePair(linkingAccountManager)
                )
            )
            .build()

        return try {
            val response = _dynamoDBClient.deleteItem(request)
            response.sdkHttpResponse().isSuccessful
        } catch (_: ConditionalCheckFailedException) {
            _logger.trace("Conditional check failed when removing link, meaning that link does not exist")
            false
        }

    }

    override fun getAll(resourceQuery: ResourceQuery): ResourceQueryResult = try {
        val comparator = getComparatorFor(resourceQuery)

        val queryPlan = if (resourceQuery.filter != null) {
            QueryPlanner(AccountsTable.queryCapabilities).build(resourceQuery.filter)
        } else {
            QueryPlan.UsingScan.fullScan()
        }

        val values = when (queryPlan) {
            is QueryPlan.UsingQueries -> query(queryPlan)
            is QueryPlan.UsingScan -> scan(queryPlan)
        }

        // FIXME avoid the toList
        val sortedValues = if (comparator != null) {
            values.sortedWith(
                if (resourceQuery.sorting.sortOrder == ResourceQuery.Sorting.SortOrder.ASCENDING) {
                    comparator
                } else {
                    comparator.reversed()
                }
            ).toList()
        } else {
            values.toList()
        }

        val validatedStartIndex = resourceQuery.pagination.startIndex.toIntOrThrow("pagination.startIndex")
        val validatedCount = resourceQuery.pagination.count.toIntOrThrow("pagination.count")

        val finalValues = sortedValues
            .drop(validatedStartIndex)
            .take(validatedCount)
            .map { it.toAccountAttributes(resourceQuery.attributesEnumeration) }
            .toList()

        ResourceQueryResult(
            finalValues,
            sortedValues.size.toLong(),
            resourceQuery.pagination.startIndex,
            resourceQuery.pagination.count
        )
    } catch (e: UnsupportedQueryException) {
        _logger.debug("Unable to process query. Reason is '{}'", e.message)
        _logger.debug(MASK_MARKER, "Unable to process query = '{}", resourceQuery)

        throw _configuration.getExceptionFactory().externalServiceException(e.message)
    }

    private fun scan(queryPlan: QueryPlan.UsingScan?): Sequence<DynamoDBItem> {
        val scanRequestBuilder = ScanRequest.builder()
            .tableName(AccountsTable.name(_configuration))

        return if (queryPlan != null && queryPlan.expression.products.isNotEmpty()) {
            val dynamoDBScan = DynamoDBQueryBuilder.buildScan(queryPlan.expression).addPkFiltering()
            scanRequestBuilder.configureWith(dynamoDBScan)
            scanSequence(scanRequestBuilder.build(), _dynamoDBClient)
                .filterWith(queryPlan.expression.products)
        } else {
            scanRequestBuilder.configureWith(filterForItemsWithAccountIdPk)
            scanSequence(scanRequestBuilder.build(), _dynamoDBClient)
        }
    }

    private fun DynamoDBScan.addPkFiltering() = DynamoDBScan(
        filterExpression = "${filterForItemsWithAccountIdPk.filterExpression} AND (${this.filterExpression})",
        valueMap = filterForItemsWithAccountIdPk.valueMap + this.valueMap,
        nameMap = filterForItemsWithAccountIdPk.nameMap + this.nameMap
    )

    private fun query(queryPlan: QueryPlan.UsingQueries): Sequence<DynamoDBItem> {
        val nOfQueries = queryPlan.queries.entries.size
        if (nOfQueries > MAX_QUERIES) {
            throw UnsupportedQueryException.QueryRequiresTooManyOperations(nOfQueries, MAX_QUERIES)
        }
        val result = linkedMapOf<String, Map<String, AttributeValue>>()
        queryPlan.queries.forEach { query ->
            val dynamoDBQuery = DynamoDBQueryBuilder.buildQuery(query.key, query.value)

            val queryRequest = QueryRequest.builder()
                .tableName(AccountsTable.name(_configuration))
                .configureWith(dynamoDBQuery)
                .build()

            querySequence(queryRequest, _dynamoDBClient)
                .filterWith(query.value)
                .forEach {
                    result[AccountsTable.accountId.from(it)] = it
                }
        }
        return result.values.asSequence()
    }

    private fun getComparatorFor(resourceQuery: ResourceQuery): Comparator<Map<String, AttributeValue>>? {
        return if (resourceQuery.sorting != null && resourceQuery.sorting.sortBy != null) {
            AccountsTable.queryCapabilities.attributeMap[resourceQuery.sorting.sortBy]
                ?.let { attribute ->
                    attribute.comparator()
                        ?: throw UnsupportedQueryException.UnsupportedSortAttribute(resourceQuery.sorting.sortBy)
                }
                ?: throw UnsupportedQueryException.UnknownSortAttribute(resourceQuery.sorting.sortBy)
        } else {
            null
        }
    }

    override fun getAll(startIndex: Long, count: Long): ResourceQueryResult {
        val validatedStartIndex = startIndex.toIntOrThrow("pagination.startIndex")
        val validatedCount = count.toIntOrThrow("pagination.count")

        val allItems = scan(null).toList()

        val paginatedItems = allItems
            .drop(validatedStartIndex)
            .take(validatedCount)
            .map { it.toAccountAttributes() }
            .toList()

        return ResourceQueryResult(paginatedItems, allItems.count().toLong(), startIndex, count)
    }

    private fun AccountAttributes.toItem(
        accountId: String,
        created: Long,
        updated: Long
    ): MutableMap<String, AttributeValue> {
        val item = mutableMapOf<String, AttributeValue>()
        item.addAttr(AccountsTable.userName, userName)
        item.addAttr(AccountsTable.active, isActive)

        item.addAttr(AccountsTable.accountId, accountId)
        item.addAttr(AccountsTable.created, created)
        item.addAttr(AccountsTable.updated, updated)

        if (!password.isNullOrEmpty()) {
            item.addAttr(AccountsTable.password, password)
        }

        if (emails != null) {
            val email = emails.primaryOrFirst
            if (email != null && !email.isEmpty) {
                item.addAttr(AccountsTable.email, email.significantValue)
            }
        }

        if (phoneNumbers != null) {
            val phone = phoneNumbers.primaryOrFirst
            if (phone != null && !phone.isEmpty) {
                item.addAttr(AccountsTable.phone, phone.significantValue)
            }
        }

        serialize(this)?.let {
            item.addAttr(AccountsTable.attributes, it)
        }

        return item
    }

    private fun serialize(accountAttributes: AccountAttributes): String? {
        val filteredAttributes =
            Attributes.of(
                removeLinkedAccounts(accountAttributes)
                    .filter {
                        !attributesToRemove.contains(it.name.value)
                    }
            )

        return if (!filteredAttributes.isEmpty) {
            jsonHandler.fromAttributes(filteredAttributes)
        } else {
            null
        }
    }

    private fun Map<String, AttributeValue>.toAccountAttributes(): AccountAttributes = toAccountAttributes(null)
    private fun Map<String, AttributeValue>.toAccountAttributes(attributesEnumeration: ResourceQuery.AttributesEnumeration?): AccountAttributes {
        val map = mutableMapOf<String, Any?>()

        map["id"] = AccountsTable.accountId.optionalFrom(this)

        forEach { (key, value) ->
            when (key) {
                AccountsTable.pk.name -> { /*ignore*/
                }
                AccountsTable.version.name -> { /*ignore*/
                }
                AccountsTable.active.name -> map["active"] = value.bool()
                AccountsTable.email.name -> {
                } // skip, emails are in attributes
                AccountsTable.phone.name -> {
                } // skip, phones are in attributes
                AccountsTable.attributes.name -> map.putAll(
                    jsonHandler.fromJson(AccountsTable.attributes.optionalFrom(this)) ?: emptyMap<String, Any>()
                )
                AccountsTable.created.name -> {
                } // skip, this goes to meta
                AccountsTable.updated.name -> {
                } // skip, this goes to meta
                AccountsTable.password.name -> {
                } // do not return passwords
                else -> map[key] = value.s()
            }
        }

        if (attributesEnumeration.includeMeta()) {
            val zoneId =
                if (map["timezone"] != null) ZoneId.of(map["timezone"].toString()) else ZoneId.of("UTC")

            map["meta"] = mapOf(
                Meta.RESOURCE_TYPE to AccountAttributes.RESOURCE_TYPE,
                "created" to
                        ZonedDateTime.ofInstant(
                            Instant.ofEpochSecond(AccountsTable.created.optionalFrom(this) ?: -1L),
                            zoneId
                        )
                            .toString(),
                "lastModified" to
                        ZonedDateTime.ofInstant(
                            Instant.ofEpochSecond(AccountsTable.updated.optionalFrom(this) ?: -1L),
                            zoneId
                        )
                            .toString()

            )
        }

        val accountAttributes = AccountAttributes.fromMap(map)

        return if (attributesEnumeration != null) {
            // Most of the attributes are in the "attributes" blob json, so the fields have to be filtered separately here
            AccountAttributes.of(accountAttributes.filter(attributesEnumeration))
        } else {
            accountAttributes
        }
    }

    private fun ResourceQuery.AttributesEnumeration?.includeMeta() =
        this == null || isNeutral || (this is ResourceQuery.Inclusions && attributes.contains("meta")) || (this is ResourceQuery.Exclusions && !attributes.contains(
            "meta"
        ))

    object AccountsTable : Table("curity-accounts") {
        val pk = KeyStringAttribute("pk")
        val accountId = UniqueStringAttribute("accountId", "ai#")
        val version = NumberLongAttribute("version")
        val userName = UniqueStringAttribute("userName", "un#")
        val email = UniqueStringAttribute("email", "em#")
        val phone = UniqueStringAttribute("phone", "pn#")
        val password = StringAttribute("password")
        val active = BooleanAttribute("active")
        val attributes = StringAttribute("attributes")
        val created = NumberLongAttribute("created")
        val updated = NumberLongAttribute("updated")

        fun keyFromAccountId(accountIdValue: String) = mapOf(
            pk.name to pk.toAttrValue(accountId.uniquenessValueFrom(accountIdValue))
        )

        fun keyFromUserName(userNameValue: String) = mapOf(
            pk.name to pk.toAttrValue(userName.uniquenessValueFrom(userNameValue))
        )

        val queryCapabilities = TableQueryCapabilities(
            indexes = listOf(
                Index.from(PrimaryKey(UniquenessBasedIndexStringAttribute(pk, accountId))),
                Index.from(PrimaryKey(UniquenessBasedIndexStringAttribute(pk, userName))),
                Index.from(PrimaryKey(UniquenessBasedIndexStringAttribute(pk, email))),
                Index.from(PrimaryKey(UniquenessBasedIndexStringAttribute(pk, phone)))
            ),
            attributeMap = mapOf(
                AccountAttributes.USER_NAME to userName,
                AccountAttributes.EMAILS to email,
                AccountAttributes.EMAILS + ".value" to email,
                AccountAttributes.PHONE_NUMBERS to phone,
                AccountAttributes.PHONE_NUMBERS + ".value" to phone,
                AccountAttributes.ACTIVE to active
            )
        )
    }

    object LinksTable : Table("curity-links") {
        val pk =
            StringCompositeAttribute2("linkedAccountId_linkedAccountDomainName") { linkedAccountId, linkedAccountDomainName -> "$linkedAccountId@$linkedAccountDomainName" }
        val localAccountId = StringAttribute("accountId")
        val linkedAccountId = StringAttribute("linkedAccountId")
        val linkedAccountDomainName = StringAttribute("linkedAccountDomainName")
        val linkingAccountManager = StringAttribute("linkingAccountManager")
        val created = NumberLongAttribute("created")

        val listLinksIndex = PartitionAndSortIndex("list-links-index", localAccountId, linkingAccountManager)

        fun createItem(
            linkingAccountManagerValue: String,
            localAccountIdValue: String,
            foreignDomainNameValue: String,
            foreignUserNameValue: String
        ) = mapOf(
            pk.toNameValuePair(foreignUserNameValue, foreignDomainNameValue),
            localAccountId.toNameValuePair(localAccountIdValue),
            linkedAccountId.toNameValuePair(foreignUserNameValue),
            linkedAccountDomainName.toNameValuePair(foreignDomainNameValue),
            linkingAccountManager.toNameValuePair(linkingAccountManagerValue),
            created.toNameValuePair(Instant.now().epochSecond)
        )
    }

    private fun versionAndAccountIdConditionExpression(version: Long, accountId: String) = object : Expression(
        _conditionExpressionBuilder
    ) {
        override val values = mapOf(
            ":oldVersion" to AccountsTable.version.toAttrValue(version),
            AccountsTable.accountId.toExpressionNameValuePair(accountId)
        )
    }

    companion object {
        private val _conditionExpressionBuilder = ExpressionBuilder(
            "#version = :oldVersion AND #accountId = :accountId",
            AccountsTable.version, AccountsTable.accountId
        )

        private val _logger: Logger = LoggerFactory.getLogger(DynamoDBUserAccountDataAccessProvider::class.java)
        private val MASK_MARKER: Marker = MarkerFactory.getMarker("MASK")

        private val attributesToRemove = listOf(
            // these are SDK attribute names and not DynamoDB table attribute names
            "active", "password", "userName", "id", "schemas"
        )

        private val NO_LINK_DESCRIPTION: String? = null

        private const val N_OF_ATTEMPTS = 3

        private fun removeLinkedAccounts(account: AccountAttributes): AccountAttributes {
            var withoutLinks = account
            for (linkedAccount in account.linkedAccounts.toList()) {
                _logger.trace(
                    MASK_MARKER,
                    "Removing linked account before persisting to accounts table '{}'",
                    linkedAccount
                )
                withoutLinks = account.removeLinkedAccounts(linkedAccount)
            }
            return withoutLinks.removeAttribute(AccountAttributes.LINKED_ACCOUNTS)
        }

        private fun DynamoDBItem.version(): Long =
            AccountsTable.version.optionalFrom(this)
                ?: throw SchemaErrorException(
                    AccountsTable,
                    AccountsTable.version
                )

        private fun DynamoDBItem.accountId(): String =
            AccountsTable.accountId.optionalFrom(this)
                ?: throw SchemaErrorException(
                    AccountsTable,
                    AccountsTable.accountId
                )

        private val filterForItemsWithAccountIdPk = DynamoDBScan(
            filterExpression = "begins_with(pk, :pkPrefix)",
            valueMap = mapOf(":pkPrefix" to AttributeValue.builder().s(AccountsTable.accountId.prefix).build()),
            nameMap = mapOf()
        )

        private const val MAX_QUERIES = 8
    }
}
