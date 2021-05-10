/*
 * Copyright (C) 2021 Curity AB. All rights reserved.
 *
 * The contents of this file are the property of Curity AB.
 * You may not copy or use this file, in either source code
 * or executable form, except in compliance with terms
 * set by Curity AB.
 *
 * For further information, please contact Curity AB.
 */

package io.curity.identityserver.plugin.dynamodb.query

import io.curity.identityserver.plugin.dynamodb.NumberLongAttribute
import io.curity.identityserver.plugin.dynamodb.StringAttribute
import io.curity.identityserver.plugin.dynamodb.token.DynamoDBDelegationDataAccessProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import se.curity.identityserver.sdk.data.authorization.DelegationStatus
import se.curity.identityserver.sdk.data.query.Filter

class DelegationQueryTests
{
    @Test
    fun testActiveByClient()
    {

        val filterExpression = Filter.LogicalExpression(
            Filter.LogicalOperator.AND,
            Filter.LogicalExpression(
                Filter.LogicalOperator.AND,
                Filter.AttributeExpression(
                    Filter.AttributeOperator.EQ,
                    "status", DelegationStatus.issued
                ),
                Filter.AttributeExpression(
                    Filter.AttributeOperator.GT,
                    "expires", 1234
                )
            ),
            Filter.AttributeExpression(
                Filter.AttributeOperator.EQ,
                "client_id", "client-one"
            )
        )

        val queryPlanner = QueryPlanner(DynamoDBDelegationDataAccessProvider.DelegationTable.queryCapabilities)

        val queryPlan = queryPlanner.build(filterExpression)

        if (queryPlan is QueryPlan.UsingScan)
        {
            fail("Query plan cannot be a scan")
            return
        }

        val query = (queryPlan as QueryPlan.UsingQueries).queries.entries.single()
        assertEquals(
            Index.from(DynamoDBDelegationDataAccessProvider.DelegationTable.clientStatusIndex),
            query.key.index
        )
        assertEquals(
            AttributeExpression(
                DynamoDBDelegationDataAccessProvider.DelegationTable.clientId,
                AttributeOperator.Eq,
                "client-one"
            ),
            query.key.partitionCondition
        )
        assertEquals(
            QueryPlan.RangeCondition.Binary(
                AttributeExpression(
                    DynamoDBDelegationDataAccessProvider.DelegationTable.status,
                    AttributeOperator.Eq,
                    DelegationStatus.issued
                )
            ),
            query.key.sortCondition
        )
        assertEquals(
            AttributeExpression(
                DynamoDBDelegationDataAccessProvider.DelegationTable.expires,
                AttributeOperator.Gt,
                1234
            ),
            query.value.single().terms.single()
        )

        val dynamoDBQuery = DynamoDBQueryBuilder.buildQuery(query.key, query.value)

        assertEquals("clientId-status-index", dynamoDBQuery.indexName)
        assertEquals("#clientId = :clientId_1 AND #status = :status_1", dynamoDBQuery.keyExpression)
        assertEquals("#expires > :expires_1", dynamoDBQuery.filterExpression)
        assertEquals(
            mapOf(
                ":expires_1" to NumberLongAttribute("").toAttrValue(1234),
                ":clientId_1" to StringAttribute("").toAttrValue("client-one"),
                ":status_1" to StringAttribute("").toAttrValue("issued")
            ),
            dynamoDBQuery.valueMap
        )
        assertEquals(
            mapOf(
                "#expires" to "expires",
                "#clientId" to "clientId",
                "#status" to "status"
            ),
            dynamoDBQuery.nameMap
        )
    }

    @Test
    fun testNotActiveByClient()
    {

        val filterExpression = Filter.LogicalExpression(
            Filter.LogicalOperator.AND,
            Filter.LogicalExpression(
                Filter.LogicalOperator.AND,
                Filter.AttributeExpression(
                    Filter.AttributeOperator.NE,
                    "status", DelegationStatus.issued
                ),
                Filter.AttributeExpression(
                    Filter.AttributeOperator.GT,
                    "expires", 1234
                )
            ),
            Filter.AttributeExpression(
                Filter.AttributeOperator.EQ,
                "client_id", "client-one"
            )
        )

        val queryPlanner = QueryPlanner(DynamoDBDelegationDataAccessProvider.DelegationTable.queryCapabilities)

        val queryPlan = queryPlanner.build(filterExpression)

        if (queryPlan is QueryPlan.UsingScan)
        {
            fail("Query plan cannot be a scan")
            return
        }

        val queries = (queryPlan as QueryPlan.UsingQueries).queries

        val firstQuery = queries.entries.first()
        assertQuery(firstQuery, AttributeOperator.Lt)

        val secondQuery = queries.entries.drop(1).first()
        assertQuery(secondQuery, AttributeOperator.Gt)
    }

    private fun assertQuery(query: Map.Entry<QueryPlan.KeyCondition, List<Product>>, operator: AttributeOperator)
    {
        val operatorString = when (operator)
        {
            AttributeOperator.Lt -> "<"
            AttributeOperator.Gt -> ">"
            else -> throw AssertionError("Unexpected operator here")
        }
        assertEquals(
            Index.from(DynamoDBDelegationDataAccessProvider.DelegationTable.clientStatusIndex),
            query.key.index
        )
        assertEquals(
            AttributeExpression(
                DynamoDBDelegationDataAccessProvider.DelegationTable.clientId,
                AttributeOperator.Eq,
                "client-one"
            ),
            query.key.partitionCondition
        )
        assertEquals(
            QueryPlan.RangeCondition.Binary(
                AttributeExpression(
                    DynamoDBDelegationDataAccessProvider.DelegationTable.status,
                    operator,
                    DelegationStatus.issued
                )
            ),
            query.key.sortCondition
        )
        assertEquals(
            AttributeExpression(
                DynamoDBDelegationDataAccessProvider.DelegationTable.expires,
                AttributeOperator.Gt,
                1234
            ),
            query.value.single().terms.single()
        )

        val dynamoDBQuery = DynamoDBQueryBuilder.buildQuery(query.key, query.value)

        assertEquals("clientId-status-index", dynamoDBQuery.indexName)
        assertEquals("#clientId = :clientId_1 AND #status $operatorString :status_1", dynamoDBQuery.keyExpression)
        assertEquals("#expires > :expires_1", dynamoDBQuery.filterExpression)
        assertEquals(
            mapOf(
                ":expires_1" to NumberLongAttribute("").toAttrValue(1234),
                ":clientId_1" to StringAttribute("").toAttrValue("client-one"),
                ":status_1" to StringAttribute("").toAttrValue("issued")
            ),
            dynamoDBQuery.valueMap
        )
        assertEquals(
            mapOf(
                "#expires" to "expires",
                "#clientId" to "clientId",
                "#status" to "status"
            ),
            dynamoDBQuery.nameMap
        )
    }

    @Test
    fun testQueryByRedirectUri()
    {
        val filterExpression =
            Filter.AttributeExpression(
                Filter.AttributeOperator.EQ,
                "redirect_uri", "https://example.com"
            )

        val queryPlanner = QueryPlanner(DynamoDBDelegationDataAccessProvider.DelegationTable.queryCapabilities)

        val queryPlan = queryPlanner.build(filterExpression)

        if (queryPlan is QueryPlan.UsingQueries)
        {
            fail("Query plan needs to be a scan")
            return
        }

        val expression = (queryPlan as QueryPlan.UsingScan).expression
        assertEquals(1, expression.products.size)
        val product = expression.products.single()
        assertEquals(1, product.terms.size)
        val term = product.terms.single()
        assertEquals(AttributeOperator.Eq, term.operator)
        assertEquals(DynamoDBDelegationDataAccessProvider.DelegationTable.redirectUri, term.attribute)
        assertEquals("https://example.com", term.value)
    }
}
