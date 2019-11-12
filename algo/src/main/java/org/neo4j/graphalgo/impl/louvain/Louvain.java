/*
 * Copyright (c) 2017-2019 "Neo4j,"
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
package org.neo4j.graphalgo.impl.louvain;

import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.HugeLongArray;
import org.neo4j.graphalgo.impl.modularity.ModularityOptimization;
import org.neo4j.graphdb.Direction;
import org.neo4j.logging.NullLog;

import java.util.concurrent.ExecutorService;

import static org.neo4j.graphalgo.core.utils.ParallelUtil.DEFAULT_BATCH_SIZE;

public final class Louvain extends Algorithm<Louvain> {

    private final int maxLevel;
    private final NodeProperties seedingValues;
    private final ExecutorService pool;
    private final int concurrency;
    private final AllocationTracker tracker;
    private final Graph rootGraph;
    private final HugeLongArray[] dendrogramm;

    public Louvain(
        int maxLevel, Graph graph,
        NodeProperties seedingValues,
        ExecutorService pool,
        int concurrency,
        AllocationTracker tracker
    ) {
        this.maxLevel = maxLevel;
        this.rootGraph = graph;
        this.seedingValues = seedingValues;
        this.pool = pool;
        this.concurrency = concurrency;
        this.tracker = tracker;
        this.dendrogramm = new HugeLongArray[maxLevel];
    }

    public void compute() {
        Graph workingGraph = rootGraph;
        NodeProperties seed = seedingValues;

        for (int level = 0; level < maxLevel; level++) {
            ModularityOptimization modularityOptimization = runModularityOptimization(workingGraph, seed);
            modularityOptimization.release();

            dendrogramm[level] = HugeLongArray.newArray(rootGraph.nodeCount(), tracker);
            for(long nodeId = 0; nodeId < rootGraph.nodeCount(); nodeId++) {
                long prevId = level == 0 ?
                    nodeId :
                    workingGraph.toMappedNodeId(dendrogramm[level - 1].get(nodeId));

                dendrogramm[level].set(nodeId, modularityOptimization.getCommunityId(prevId));
            }

            workingGraph = summarizeGraph(workingGraph, modularityOptimization);
            seed = new OriginalIdNodeProperties(workingGraph);
        }
    }

    private ModularityOptimization runModularityOptimization(Graph louvainGraph, NodeProperties seed) {
        return new ModularityOptimization(
            louvainGraph,
            Direction.BOTH,
            10,
            seed,
            concurrency,
            DEFAULT_BATCH_SIZE,
            pool,
            tracker,
            NullLog.getInstance()
        )
            .withProgressLogger(progressLogger)
            .withTerminationFlag(terminationFlag)
            .compute();
    }

    private Graph summarizeGraph(Graph workingGraph, ModularityOptimization modularityOptimization) {
        SubGraphGenerator.NodeImporter nodeImporter = new SubGraphGenerator.NodeImporter(
            workingGraph.nodeCount(),
            tracker
        );

        workingGraph.forEachNode((nodeId) -> {
            nodeImporter.addNode(modularityOptimization.getCommunityId(nodeId));
            return true;
        });

        SubGraphGenerator.RelImporter relImporter = nodeImporter.build();

        workingGraph.forEachNode((nodeId) -> {
            long communityId = modularityOptimization.getCommunityId(nodeId);
            workingGraph.forEachRelationship(nodeId, Direction.BOTH, 1.0, (s, t, w) -> {
                relImporter.add(communityId, modularityOptimization.getCommunityId(t));
                return true;
            });
            return true;
        });

        return relImporter.build();
    }

    HugeLongArray[] dendrogramm() {
        return this.dendrogramm;
    }

    @Override
    public void release() {

    }

    @Override
    public Louvain me() {
        return this;
    }

    class OriginalIdNodeProperties implements NodeProperties {
        private final Graph graph;

        public OriginalIdNodeProperties(Graph graph) {
            this.graph = graph;
        }

        @Override
        public double nodeProperty(long nodeId) {
            return graph.toOriginalNodeId(nodeId);
        }
    }
}
