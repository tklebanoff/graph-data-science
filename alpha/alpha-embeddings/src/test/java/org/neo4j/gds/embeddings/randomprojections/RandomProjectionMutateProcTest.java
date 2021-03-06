/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gds.embeddings.randomprojections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.graphalgo.AlgoBaseProc;
import org.neo4j.graphalgo.GdsCypher;
import org.neo4j.graphalgo.Orientation;
import org.neo4j.graphalgo.core.CypherMapWrapper;
import org.neo4j.graphalgo.functions.NodePropertyFunc;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RandomProjectionMutateProcTest extends RandomProjectionProcTest<RandomProjectionMutateConfig>{

    @BeforeEach
    void setupNodePropertyFunc() throws Exception {
        registerFunctions(
            NodePropertyFunc.class
        );
    }

    @Override
    public Class<? extends AlgoBaseProc<RandomProjection, RandomProjection, RandomProjectionMutateConfig>> getProcedureClazz() {
        return RandomProjectionMutateProc.class;
    }

    @Override
    public RandomProjectionMutateConfig createConfig(CypherMapWrapper userInput) {
        return RandomProjectionMutateConfig.of(getUsername(), Optional.empty(), Optional.empty(), userInput);
    }

    @Override
    public CypherMapWrapper createMinimalConfig(CypherMapWrapper userInput) {
        CypherMapWrapper minimalConfig = super.createMinimalConfig(userInput);

        if (!minimalConfig.containsKey("mutateProperty")) {
            return minimalConfig.withString("mutateProperty", "embedding");
        }
        return minimalConfig;
    }

    @ParameterizedTest
    @MethodSource("org.neo4j.gds.embeddings.randomprojections.RandomProjectionProcTest#weights")
    void shouldMutateNonZeroEmbeddings(List<Float> weights) {
        String loadedGraphName = "loadGraph";

        var graphCreateQuery = GdsCypher.call()
            .withNodeLabel("Node")
            .withRelationshipType("REL", Orientation.UNDIRECTED)
            .graphCreate(loadedGraphName)
            .yields();

        runQuery(graphCreateQuery);

        int embeddingSize = 128;
        GdsCypher.ParametersBuildStage queryBuilder = GdsCypher.call()
            .explicitCreation(loadedGraphName)
            .algo("gds.alpha.randomProjection")
            .mutateMode()
            .addParameter("embeddingSize", embeddingSize)
            .addParameter("mutateProperty", "embedding");

        if (!weights.isEmpty()) {
            queryBuilder.addParameter("iterationWeights", weights);
        }
        String query = queryBuilder.yields();

        runQuery(query);

        runQueryWithRowConsumer("MATCH (n:Node) RETURN gds.util.nodeProperty('loadGraph', id(n), 'embedding') as embedding", row -> {
            float[] embeddings = (float[]) row.get("embedding");
            assertEquals(embeddingSize, embeddings.length);
            boolean allMatch = true;
            for (float embedding : embeddings) {
                if (Float.compare(embedding, 0.0F) != 0) {
                    allMatch = false;
                    break;
                }
            }
            assertFalse(allMatch);
        });
    }
}
