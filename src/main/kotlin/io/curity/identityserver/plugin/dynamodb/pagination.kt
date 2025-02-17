/*
 *  Copyright 2021 Curity AB
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

import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.ScanRequest

// Returns a sequence with the items produced by a query, handling pagination if needed
fun querySequence(request: QueryRequest, client: DynamoDBClient) = sequence {
    var response = client.query(request)
    if (response.hasItems()) {
        response.items().forEach {
            yield(it)
        }
        while (response.hasLastEvaluatedKey()) {
            val newRequest = request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build()
            response = client.query(newRequest)
            if (response.hasItems()) {
                response.items().forEach {
                    yield(it)
                }
            }
        }
    }
}

// Returns a sequence with the items produced by a query, handling pagination if needed
fun scanSequence(request: ScanRequest, client: DynamoDBClient) = sequence {
    var response = client.scan(request)
    if (response.hasItems()) {
        response.items().forEach {
            yield(it)
        }
        while (response.hasLastEvaluatedKey()) {
            val newRequest = request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build()
            response = client.scan(newRequest)
            if (response.hasItems()) {
                response.items().forEach {
                    yield(it)
                }
            }
        }
    }
}

fun count(request: QueryRequest, client: DynamoDBClient): Long {
    var response = client.query(request)
    var counter = response.count().toLong()
    while (response.hasLastEvaluatedKey()) {
        val newRequest = request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build()
        response = client.query(newRequest)
        counter += response.count()
    }

    return counter
}

fun count(request: ScanRequest, client: DynamoDBClient): Long {
    var response = client.scan(request)
    var counter = response.count().toLong()
    while (response.hasLastEvaluatedKey()) {
        val newRequest = request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build()
        response = client.scan(newRequest)
        counter += response.count()
    }

    return counter
}

