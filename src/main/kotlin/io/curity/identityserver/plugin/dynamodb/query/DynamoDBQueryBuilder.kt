package io.curity.identityserver.plugin.dynamodb.query

import io.curity.identityserver.plugin.dynamodb.DynamoDBAttribute
import io.curity.identityserver.plugin.dynamodb.DynamoDBQuery
import io.curity.identityserver.plugin.dynamodb.DynamoDBScan
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

class DynamoDBQueryBuilder
{
    companion object
    {
        fun buildQuery(keyCondition: QueryPlan.KeyCondition, products: List<Product>): DynamoDBQuery
        {
            val builder = DynamoDBQueryBuilder()
            val filterExpression = builder.toDynamoExpression(products)
            val keyExpression = builder.toDynamoExpression(keyCondition)
            return DynamoDBQuery(
                keyCondition.index.name,
                keyExpression,
                filterExpression,
                builder.valueMap,
                builder.nameMap
            )
        }

        fun buildScan(expression: DisjunctiveNormalForm): DynamoDBScan
        {
            val builder = DynamoDBQueryBuilder()
            val filterExpression = builder.toDynamoExpression(expression.products)
            return DynamoDBScan(
                filterExpression,
                builder.valueMap,
                builder.nameMap
            )
        }
    }

    private val valueMap = mutableMapOf<String, AttributeValue>()
    private val nameMap = mutableMapOf<String, String>()
    private val valueAliasCounter = mutableMapOf<String, Int>()

    private fun toDynamoExpression(keyCondition: QueryPlan.KeyCondition): String
    {
        val partitionExpression = toDynamoExpression(keyCondition.partitionCondition)
        return if (keyCondition.sortCondition == null)
        {
            partitionExpression
        } else
        {
            "$partitionExpression AND ${toDynamoExpression(keyCondition.sortCondition)}"
        }
    }

    private fun toDynamoExpression(rangeExpression: QueryPlan.RangeCondition) = when (rangeExpression)
    {
        is QueryPlan.RangeCondition.Binary -> toDynamoExpression(rangeExpression.attributeExpression)
        is QueryPlan.RangeCondition.Between ->
        {
            val hashName = hashNameFor(rangeExpression.attribute)
            val colonNameLower = colonNameFor(rangeExpression.attribute, rangeExpression.lower)
            val colonNameHigher = colonNameFor(rangeExpression.attribute, rangeExpression.higher)

            "$hashName BETWEEN $colonNameLower AND $colonNameHigher"
        }
    }

    private fun toDynamoExpression(products: Iterable<Product>) =
        products.joinToString(" OR ") { toDynamoExpression(it) }

    private fun toDynamoExpression(product: Product): String =
        product.terms.joinToString(" AND ") { toDynamoExpression(it) }

    private fun toDynamoExpression(it: AttributeExpression): String
    {
        val hashName = hashNameFor(it.attribute)
        val colonName = colonNameFor(it.attribute, it.value)
        return it.operator.toDynamoOperator(hashName, colonName)
    }

    private fun hashNameFor(attribute: DynamoDBAttribute<*>): String
    {
        nameMap[attribute.hashName] = attribute.name
        return attribute.hashName
    }

    private fun colonNameFor(attribute: DynamoDBAttribute<*>, value: Any): String
    {
        val counter = valueAliasCounter.merge(attribute.name, 1) { old, new -> old + new } ?: 1
        val colonName = colonName(attribute.name, counter)
        valueMap[colonName] = attribute.toAttrValueWithCast(value)
        return colonName
    }

    private fun colonName(name: String, counter: Int) = ":${name}_$counter"
}