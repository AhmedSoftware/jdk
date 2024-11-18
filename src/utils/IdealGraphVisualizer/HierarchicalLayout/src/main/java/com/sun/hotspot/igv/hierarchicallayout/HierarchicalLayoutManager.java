/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */
package com.sun.hotspot.igv.hierarchicallayout;

import static com.sun.hotspot.igv.hierarchicallayout.LayoutNode.*;
import com.sun.hotspot.igv.layout.Link;
import com.sun.hotspot.igv.layout.Vertex;
import java.awt.Point;
import java.util.*;


public class HierarchicalLayoutManager extends LayoutManager {

    public HierarchicalLayoutManager() {
        setCutEdges(false);
    }

    @Override
    public void setCutEdges(boolean enable) {
        maxLayerLength = enable ? 10 : -1;
    }

    @Override
    public void setCutEdges(boolean enable) {
        maxLayerLength = enable ? 10 : -1;
    }

        // STEP 2: Assign layers and create dummy nodes
        LayerManager.apply(layoutGraph, maxLayerLength);

        // STEP 3: Crossing Reduction
        CrossingReduction.apply(layoutGraph);

        // STEP 4: Assign X coordinates
        AssignXCoordinates.apply(layoutGraph);

        // STEP 5: Write back to interface
        WriteResult.apply(layoutGraph);
    }

    public static class ReverseEdges {

        static public void apply(LayoutGraph graph) {
            removeSelfEdges(graph);
            reverseRootInputs(graph);
            depthFirstSearch(graph);

            for (LayoutNode node : graph.getLayoutNodes()) {
                node.computeReversedLinkPoints();
            }
        }

        private static void removeSelfEdges(LayoutGraph graph) {
            for (LayoutNode node : graph.getLayoutNodes()) {
                Iterator<LayoutEdge> edgeIterator = node.getSuccessors().iterator();
                while (edgeIterator.hasNext()) {
                    LayoutEdge edge = edgeIterator.next();
                    if (edge.getTo() == node) {
                        edgeIterator.remove();
                        node.getPredecessors().remove(edge);
                    }
                }
            }
        }

        private static void reverseRootInputs(LayoutGraph graph) {
            for (LayoutNode node : graph.getLayoutNodes()) {
                if (node.getVertex().isRoot()) {
                    for (LayoutEdge predEdge : new ArrayList<>(node.getPredecessors())) {
                        reverseEdge(predEdge);
                    }
                }
            }
        }

        public static void reverseEdge(LayoutEdge edge) {
            edge.reverse();

            LayoutNode fromNode = edge.getFrom();
            LayoutNode toNode = edge.getTo();
            int relativeFrom = edge.getRelativeFromX();
            int relativeTo = edge.getRelativeToX();

            edge.setFrom(toNode);
            edge.setTo(fromNode);
            edge.setRelativeFromX(relativeTo);
            edge.setRelativeToX(relativeFrom);

            fromNode.getSuccessors().remove(edge);
            fromNode.getPredecessors().add(edge);
            toNode.getPredecessors().remove(edge);
            toNode.getSuccessors().add(edge);
        }

        private static void depthFirstSearch(LayoutGraph graph) {
            Set<LayoutNode> visited = new HashSet<>();
            Set<LayoutNode> active = new HashSet<>();

            for (LayoutNode startNode : graph.getLayoutNodes()) {
                Deque<LayoutNode> stack = new ArrayDeque<>();
                stack.push(startNode);

                while (!stack.isEmpty()) {
                    LayoutNode node = stack.pop();

                    if (visited.contains(node)) {
                        active.remove(node);
                        continue;
                    }

                    stack.push(node);
                    visited.add(node);
                    active.add(node);

                    for (LayoutEdge edge : new ArrayList<>(node.getSuccessors())) {
                        LayoutNode successor = edge.getTo();
                        if (active.contains(successor)) {
                            reverseEdge(edge);
                        } else if (!visited.contains(successor)) {
                            stack.push(successor);
                        }
                    }
                }
            }
        }
    }

    public static class LayerManager {

        private static void assignLayerDownwards(LayoutGraph graph) {
            ArrayList<LayoutNode> workingList = new ArrayList<>();

            // add all root nodes to layer 0
            for (LayoutNode node : graph.getLayoutNodes()) {
                if (!node.hasPredecessors()) {
                    workingList.add(node);
                    node.setLayer(0);
                }
            }

            // assign layers downwards starting from roots
            int layer = 1;
            while (!workingList.isEmpty()) {
                ArrayList<LayoutNode> newWorkingList = new ArrayList<>();
                for (LayoutNode node : workingList) {
                    for (LayoutEdge succEdge : node.getSuccessors()) {
                        LayoutNode succNode = succEdge.getTo();
                        if (succNode.getLayer() == -1) {
                            // This node was not assigned before.
                            boolean assignedPred = true;
                            for (LayoutEdge predEdge : succNode.getPredecessors()) {
                                LayoutNode predNode = predEdge.getFrom();
                                if (predNode.getLayer() == -1 || predNode.getLayer() >= layer) {
                                    // This now has an unscheduled successor or a successor that was scheduled only in this round.
                                    assignedPred = false;
                                    break;
                                }
                            }
                            if (assignedPred) {
                                // This successor node can be assigned.
                                succNode.setLayer(layer);
                                newWorkingList.add(succNode);
                            }
                        }
                    }
                }
                workingList = newWorkingList;
                layer++;
            }

            int layerCount = layer - 1;
            for (LayoutNode n : graph.getLayoutNodes()) {
                n.setLayer((layerCount - 1 - n.getLayer()));
            }
        }

        private static void assignLayerUpwards(LayoutGraph graph) {
            ArrayList<LayoutNode> workingList = new ArrayList<>();
            // add all leaves to working list, reset layer of non-leave nodes
            for (LayoutNode node : graph.getLayoutNodes()) {
                if (!node.hasSuccessors()) {
                    workingList.add(node);
                } else {
                    node.setLayer(-1);
                }
            }

            // assign layer upwards starting from leaves
            // sinks non-leave nodes down as much as possible
            int layer = 1;
            while (!workingList.isEmpty()) {
                ArrayList<LayoutNode> newWorkingList = new ArrayList<>();
                for (LayoutNode node : workingList) {
                    if (node.getLayer() < layer) {
                        for (LayoutEdge predEdge : node.getPredecessors()) {
                            LayoutNode predNode = predEdge.getFrom();
                            if (predNode.getLayer() == -1) {
                                // This node was not assigned before.
                                boolean assignedSucc = true;
                                for (LayoutEdge succEdge : predNode.getSuccessors()) {
                                    LayoutNode succNode = succEdge.getTo();
                                    if (succNode.getLayer() == -1 || succNode.getLayer() >= layer) {
                                        // This now has an unscheduled successor or a successor that was scheduled only in this round.
                                        assignedSucc = false;
                                        break;
                                    }
                                }

                                if (assignedSucc) {
                                    // This predecessor node can be assigned.
                                    predNode.setLayer(layer);
                                    newWorkingList.add(predNode);
                                }
                            }
                        }
                    } else {
                        newWorkingList.add(node);
                    }
                }

                workingList = newWorkingList;
                layer++;
            }

            int layerCount = layer - 1;
            for (LayoutNode n : graph.getLayoutNodes()) {
                n.setLayer((layerCount - 1 - n.getLayer()));
            }

            graph.initLayers(layerCount);
        }


        static private void assignLayers(LayoutGraph graph) {
            assignLayerDownwards(graph);
            assignLayerUpwards(graph);
        }

        static private void createDummyNodes(LayoutGraph graph, int maxLayerLength) {
            List<LayoutNode> layoutNodes = new ArrayList<>(graph.getLayoutNodes());
            layoutNodes.sort(LAYOUT_NODE_DEGREE_COMPARATOR);

            // Generate initial ordering
            HashSet<LayoutNode> visited = new HashSet<>();
            for (LayoutNode layoutNode : layoutNodes) {
                if (layoutNode.getLayer() == 0) {
                    graph.getLayer(0).add(layoutNode);
                    visited.add(layoutNode);
                } else if (!layoutNode.hasPredecessors()) {
                    graph.getLayer(layoutNode.getLayer()).add(layoutNode);
                    visited.add(layoutNode);
                }
            }

            for (LayoutNode layoutNode : layoutNodes) {
                graph.createDummiesForNodeSuccessor(layoutNode, maxLayerLength);
            }

            for (int i = 0; i < graph.getLayerCount() - 1; i++) {
                for (LayoutNode n : graph.getLayer(i)) {
                    for (LayoutEdge e : n.getSuccessors()) {
                        if (e.getTo().isDummy()) continue;
                        if (!visited.contains(e.getTo())) {
                            visited.add(e.getTo());
                            graph.getLayer(i + 1).add(e.getTo());
                            e.getTo().setLayer(i + 1);
                        }
                    }
                }
            }
        }

        static public void apply(LayoutGraph graph, int maxLayerLength) {
            assignLayers(graph);
            createDummyNodes(graph, maxLayerLength);
            graph.updatePositions();
        }
    }

    public static class CrossingReduction {

        public static void apply(LayoutGraph graph) {
            for (int i = 0; i < CROSSING_ITERATIONS; i++) {
                downSweep(graph);
                upSweep(graph);
            }
            downSweep(graph);
            graph.updatePositions();
        }

        private static void doAveragePositions(LayoutLayer layer) {
            for (LayoutNode node : layer) {
                node.setWeightedPosition(node.getBarycenterX());
            }
            layer.sort(CROSSING_NODE_COMPARATOR);
            int x = 0;
            for (LayoutNode n : layer) {
                n.setWeightedPosition(x);
                x += n.getOuterWidth() + NODE_OFFSET;
            }
        }

        private static void doMedianPositions(LayoutLayer layer, boolean usePred) {
            for (LayoutNode node : layer) {
                int size = usePred ? node.getPredecessors().size() : node.getSuccessors().size();
                if (size == 0) continue;
                float[] values = new float[size];
                for (int j = 0; j < size; j++) {
                    LayoutNode predNode = usePred ? node.getPredecessors().get(j).getFrom() : node.getSuccessors().get(j).getTo();
                    values[j] = predNode.getWeightedPosition();
                }
                Arrays.sort(values);
                if (values.length % 2 == 0) {
                    node.setWeightedPosition((values[size / 2 - 1] + values[size / 2]) / 2);
                } else {
                    node.setWeightedPosition(values[size / 2]);
                }
            }
            layer.sort(CROSSING_NODE_COMPARATOR);
            int x = 0;
            for (LayoutNode n : layer) {
                n.setWeightedPosition(x);
                x += n.getOuterWidth() + NODE_OFFSET;
            }
        }

        private static void placeLeavesAndRoots(LayoutLayer layer, boolean usePred) {
            // Nodes that have no adjacent nodes on the neighboring layer:
            // leave fixed in their current positions with non-fixed nodes sorted into the remaining positions
            for (int j = 0; j < layer.size(); j++) {
                LayoutNode node = layer.get(j);
                if (usePred ? !node.hasPredecessors() : !node.hasSuccessors()) {
                    float prevWeight = (j > 0) ? layer.get(j - 1).getWeightedPosition() : 0;
                    float nextWeight = (j < layer.size() - 1) ? layer.get(j + 1).getWeightedPosition() : 0;
                    node.setWeightedPosition((prevWeight + nextWeight) / 2);
                }
            }
            layer.sort(CROSSING_NODE_COMPARATOR);
            int x = 0;
            for (LayoutNode n : layer) {
                n.setWeightedPosition(x);
                x += n.getOuterWidth() + NODE_OFFSET;
            }
        }

        private static void downSweep(LayoutGraph graph) {
            for (int i = 0; i < graph.getLayerCount(); i++) {
                doAveragePositions(graph.getLayer(i));
            }
            for (int i = 1; i < graph.getLayerCount(); i++) {
                doMedianPositions(graph.getLayer(i), true);
                placeLeavesAndRoots(graph.getLayer(i), true);
            }
        }

        private static void upSweep(LayoutGraph graph) {
            for (int i = graph.getLayerCount() - 1; i >= 0; i--) {
                doAveragePositions(graph.getLayer(i));
            }
            for (int i = graph.getLayerCount() - 2; i >= 0; i--) {
                doMedianPositions(graph.getLayer(i), false);
                placeLeavesAndRoots(graph.getLayer(i), false);
            }
        }
    }

    public static class AssignXCoordinates {

        static int[][] space;
        static LayoutNode[][] downProcessingOrder;
        static LayoutNode[][] upProcessingOrder;

        static private void createArrays(LayoutGraph graph) {
            space = new int[graph.getLayerCount()][];
            downProcessingOrder = new LayoutNode[graph.getLayerCount()][];
            upProcessingOrder = new LayoutNode[graph.getLayerCount()][];
            for (int i = 0; i < graph.getLayerCount(); i++) {
                LayoutLayer layer = graph.getLayer(i);
                space[i] = new int[layer.size()];
                downProcessingOrder[i] = new LayoutNode[layer.size()];
                upProcessingOrder[i] = new LayoutNode[layer.size()];
                int curX = 0;
                for (int j = 0; j < layer.size(); j++) {
                    space[i][j] = curX;
                    LayoutNode node = layer.get(j);
                    curX += node.getOuterWidth() + NODE_OFFSET;
                    downProcessingOrder[i][j] = node;
                    upProcessingOrder[i][j] = node;
                }
                Arrays.sort(downProcessingOrder[i], NODE_PROCESSING_DOWN_COMPARATOR);
                Arrays.sort(upProcessingOrder[i], NODE_PROCESSING_UP_COMPARATOR);
            }
        }

        static private void initialPositions(LayoutGraph graph) {
            for (LayoutNode layoutNode : graph.getLayoutNodes()) {
                layoutNode.setX(space[layoutNode.getLayer()][layoutNode.getPos()]);
            }
            for (LayoutNode dummyNode : graph.getDummyNodes()) {
                dummyNode.setX(space[dummyNode.getLayer()][dummyNode.getPos()]);
            }
        }

        static public void apply(LayoutGraph graph) {
            createArrays(graph);
            initialPositions(graph);
            for (int i = 0; i < SWEEP_ITERATIONS; i++) {
                sweepDown(graph);
                sweepUp(graph);
            }
            graph.optimizeBackEdgeCrossings();
            graph.straightenEdges();
        }

        static private void processRow(int[] space, LayoutNode[] processingOrder) {
            Arrays.sort(processingOrder, DUMMY_NODES_THEN_OPTIMAL_X);
            TreeSet<LayoutNode> treeSet = new TreeSet<>(NODE_POS_COMPARATOR);
            for (LayoutNode node : processingOrder) {
                int minX = Integer.MIN_VALUE;
                SortedSet<LayoutNode> headSet = treeSet.headSet(node, false);
                if (!headSet.isEmpty()) {
                    LayoutNode leftNeighbor = headSet.last();
                    minX = leftNeighbor.getOuterLeft() + space[node.getPos()] - space[leftNeighbor.getPos()];
                }

                int maxX = Integer.MAX_VALUE;
                SortedSet<LayoutNode> tailSet = treeSet.tailSet(node, false);
                if (!tailSet.isEmpty()) {
                    LayoutNode rightNeighbor = tailSet.first();
                    maxX = rightNeighbor.getOuterLeft() + space[node.getPos()] - space[rightNeighbor.getPos()];
                }

                node.setX(Math.min(Math.max(node.getOptimalX(), minX), maxX));
                treeSet.add(node);
            }
        }

        static private void sweepUp(LayoutGraph graph) {
            for (int i = graph.getLayerCount() - 2; i >= 0; i--) {
                for (LayoutNode node : upProcessingOrder[i]) {
                    node.setOptimalX(node.calculateOptimalXFromSuccessors());
                }
                processRow(space[i], upProcessingOrder[i]);
            }
        }

        static private void sweepDown(LayoutGraph graph) {
            for (int i = 1; i < graph.getLayerCount(); i++) {
                for (LayoutNode node : downProcessingOrder[i]) {
                    node.setOptimalX(node.calculateOptimalXFromPredecessors());
                }
                processRow(space[i], downProcessingOrder[i]);
            }
        }
    }

    public static class WriteResult {

        private static HashMap<Link, List<Point>> computeLinkPositions(LayoutGraph graph) {
            HashMap<Link, List<Point>> linkToSplitEndPoints = new HashMap<>();
            HashMap<Link, List<Point>> linkPositions = new HashMap<>();

            for (LayoutNode layoutNode : graph.getLayoutNodes()) {
                for (LayoutEdge predEdge : layoutNode.getPredecessors()) {
                    LayoutNode fromNode = predEdge.getFrom();
                    LayoutNode toNode = predEdge.getTo();

                    ArrayList<Point> linkPoints = new ArrayList<>();
                    // input edge stub
                    linkPoints.add(new Point(predEdge.getEndX(), toNode.getTop()));
                    linkPoints.add(new Point(predEdge.getEndX(), graph.getLayer(toNode.getLayer()).getTop() - LAYER_OFFSET));

                    LayoutEdge curEdge = predEdge;
                    while (fromNode.isDummy() && fromNode.hasPredecessors()) {
                        linkPoints.add(new Point(fromNode.getCenterX(), graph.getLayer(fromNode.getLayer()).getBottom() + LAYER_OFFSET));
                        linkPoints.add(new Point(fromNode.getCenterX(), graph.getLayer(fromNode.getLayer()).getTop() - LAYER_OFFSET));
                        curEdge = fromNode.getPredecessors().get(0);
                        fromNode = curEdge.getFrom();
                    }
                    linkPoints.add(new Point(curEdge.getStartX(), graph.getLayer(fromNode.getLayer()).getBottom() + LAYER_OFFSET));
                    // output edge stub
                    linkPoints.add(new Point(curEdge.getStartX(), fromNode.getBottom()));

                    if (predEdge.isReversed()) {
                        for (Point relativeEnd : toNode.getReversedLinkEndPoints().get(predEdge.getLink())) {
                            Point endPoint = new Point(toNode.getLeft() + relativeEnd.x, toNode.getTop() + relativeEnd.y);
                            linkPoints.add(0, endPoint);
                        }

                        if (!fromNode.isDummy()) {
                            if (fromNode.getReversedLinkStartPoints().containsKey(predEdge.getLink())) {
                                for (Point relativeStart : fromNode.getReversedLinkStartPoints().get(predEdge.getLink())) {
                                    Point startPoint = new Point(fromNode.getLeft() + relativeStart.x, fromNode.getTop() + relativeStart.y);
                                    linkPoints.add(startPoint);
                                }
                            }
                        }
                    } else {
                        Collections.reverse(linkPoints);
                    }

                    if (fromNode.isDummy()) {
                        if (predEdge.isReversed()) {
                            Collections.reverse(linkPoints);
                        }
                        linkToSplitEndPoints.put(predEdge.getLink(), linkPoints);

                    } else {
                        linkPositions.put(predEdge.getLink(), linkPoints);
                    }
                }
            }

            for (LayoutNode layoutNode : graph.getLayoutNodes()) {
                for (LayoutEdge succEdge : layoutNode.getSuccessors()) {
                    if (succEdge.getLink() == null) continue;

                    LayoutNode fromNode = succEdge.getFrom();
                    LayoutNode toNode = succEdge.getTo();

                    ArrayList<Point> linkPoints = new ArrayList<>();
                    linkPoints.add(new Point(succEdge.getStartX(), fromNode.getBottom()));
                    linkPoints.add(new Point(succEdge.getStartX(), graph.getLayer(fromNode.getLayer()).getBottom() + LAYER_OFFSET));

                    LayoutEdge curEdge = succEdge;
                    while (toNode.isDummy() && toNode.hasSuccessors()) {
                        linkPoints.add(new Point(toNode.getCenterX(), graph.getLayer(toNode.getLayer()).getTop() - LAYER_OFFSET));
                        linkPoints.add(new Point(toNode.getCenterX(), graph.getLayer(toNode.getLayer()).getBottom() + LAYER_OFFSET));
                        curEdge = toNode.getSuccessors().get(0);
                        toNode = curEdge.getTo();
                    }
                    linkPoints.add(new Point(curEdge.getEndX(), graph.getLayer(toNode.getLayer()).getTop() - LAYER_OFFSET));
                    linkPoints.add(new Point(curEdge.getEndX(), toNode.getTop()));

                    if (succEdge.isReversed()) {
                        Collections.reverse(linkPoints);

                        if (fromNode.getReversedLinkStartPoints().containsKey(succEdge.getLink())) {
                            for (Point relativeStart : fromNode.getReversedLinkStartPoints().get(succEdge.getLink())) {
                                Point startPoint = new Point(fromNode.getLeft() + relativeStart.x, fromNode.getTop() + relativeStart.y);
                                linkPoints.add(startPoint);
                            }
                        }

                        if (!toNode.isDummy()) {
                            if (toNode.getReversedLinkEndPoints().containsKey(succEdge.getLink())) {
                                for (Point relativeEnd : toNode.getReversedLinkEndPoints().get(succEdge.getLink())) {
                                    Point endPoint = new Point(toNode.getLeft() + relativeEnd.x, toNode.getTop() + relativeEnd.y);
                                    linkPoints.add(0, endPoint);
                                }
                            }
                        }
                    }

                    if (linkToSplitEndPoints.containsKey(succEdge.getLink())) {
                        if (succEdge.isReversed()) {
                            Collections.reverse(linkPoints);
                        }
                        linkPoints.add(null);
                        linkPoints.addAll(linkToSplitEndPoints.get(succEdge.getLink()));
                        if (succEdge.isReversed()) {
                            Collections.reverse(linkPoints);
                        }
                    }
                    linkPositions.put(succEdge.getLink(), linkPoints);
                }
            }

            return linkPositions;
        }

        public static void apply(LayoutGraph graph) {
            // Assign Y coordinates
            graph.positionLayers();

            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;

            HashMap<Link, List<Point>> linkPositions = computeLinkPositions(graph);

            for (List<Point> points : linkPositions.values()) {
                for (Point point : points) {
                    if (point != null) {
                        minX = Math.min(minX, point.x);
                        minY = Math.min(minY, point.y);
                    }
                }
            }

            for (LayoutNode layoutNode : graph.getLayoutNodes()) {
                minX = Math.min(minX, layoutNode.getX());
                minY = Math.min(minY, layoutNode.getY());
            }

            for (LayoutNode dummyNode : graph.getDummyNodes()) {
                minX = Math.min(minX, dummyNode.getX());
                minY = Math.min(minY, dummyNode.getY());
            }

            for (LayoutLayer layer : graph.getLayers()) {
                minY = Math.min(minY, layer.getTop());
            }

            for (LayoutNode layoutNode : graph.getLayoutNodes()) {
                layoutNode.setX(layoutNode.getX() - minX);
                layoutNode.setY(layoutNode.getY() - minY);
            }
            for (LayoutNode dummyNode : graph.getDummyNodes()) {
                dummyNode.setX(dummyNode.getX() - minX);
                dummyNode.setY(dummyNode.getY() - minY);
            }

            for (LayoutLayer layer : graph.getLayers()) {
                layer.moveLayerVertically(-minY);
            }

            // Shift vertices by minX/minY
            for (LayoutNode layoutNode : graph.getLayoutNodes()) {
                Vertex vertex = layoutNode.getVertex();
                vertex.setPosition(new Point(layoutNode.getLeft(), layoutNode.getTop()));
            }

            // shift links by minX/minY
            for (Map.Entry<Link, List<Point>> entry : linkPositions.entrySet()) {
                Link link = entry.getKey();
                List<Point> points = entry.getValue();
                for (Point p : points) {
                    if (p != null) {
                        p.x -= minX;
                        p.y -= minY;
                    }
                }

                // write points back to links
                link.setControlPoints(points);
            }
        }
    }
}
