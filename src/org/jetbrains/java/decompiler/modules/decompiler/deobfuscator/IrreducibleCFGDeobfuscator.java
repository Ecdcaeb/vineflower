/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler.modules.decompiler.deobfuscator;

import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.stats.BasicBlockStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;


public class IrreducibleCFGDeobfuscator {


  public static boolean isStatementIrreducible(Statement statement) {

    class Node {
      public Integer id;
      public Set<Node> preds = new HashSet<Node>();
      public Set<Node> succs = new HashSet<Node>();

      public Node(Integer id) {
        this.id = id;
      }
    }

    HashMap<Integer, Node> mapNodes = new HashMap<Integer, Node>();

    // checking exceptions and creating nodes
    for (Statement stat : statement.getStats()) {
      if (!stat.getSuccessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty()) {
        return false;
      }

      mapNodes.put(stat.id, new Node(stat.id));
    }

    // connecting nodes
    for (Statement stat : statement.getStats()) {
      Node node = mapNodes.get(stat.id);

      for (Statement succ : stat.getNeighbours(StatEdge.TYPE_REGULAR, Statement.DIRECTION_FORWARD)) {
        Node nodeSucc = mapNodes.get(succ.id);

        node.succs.add(nodeSucc);
        nodeSucc.preds.add(node);
      }
    }

    // transforming and reducing the graph
    for (; ; ) {
      int ttype = 0;
      Node node = null;

      for (Node nd : mapNodes.values()) {
        if (nd.succs.contains(nd)) { // T1
          ttype = 1;
        }
        else if (nd.preds.size() == 1) { // T2
          ttype = 2;
        }

        if (ttype != 0) {
          node = nd;
          break;
        }
      }

      if (node != null) {
        if (ttype == 1) {
          node.succs.remove(node);
          node.preds.remove(node);
        }
        else {
          Node pred = node.preds.iterator().next();

          pred.succs.addAll(node.succs);
          pred.succs.remove(node);

          for (Node succ : node.succs) {
            succ.preds.remove(node);
            succ.preds.add(pred);
          }

          mapNodes.remove(node.id);
        }
      }
      else { // no transformation applicable
        return mapNodes.size() > 1; // reducible iff one node remains
      }
    }
  }


  private static Statement getCandidateForSplitting(Statement statement) {

    Statement candidateForSplitting = null;
    int sizeCandidateForSplitting = Integer.MAX_VALUE;
    int succsCandidateForSplitting = Integer.MAX_VALUE;

    for (Statement stat : statement.getStats()) {

      Set<Statement> setPreds = stat.getNeighboursSet(StatEdge.TYPE_REGULAR, Statement.DIRECTION_BACKWARD);

      if (setPreds.size() > 1) {
        int succCount = stat.getNeighboursSet(StatEdge.TYPE_REGULAR, Statement.DIRECTION_FORWARD).size();
        if (succCount <= succsCandidateForSplitting) {
          int size = getStatementSize(stat) * (setPreds.size() - 1);

          if (succCount < succsCandidateForSplitting ||
              size < sizeCandidateForSplitting) {
            candidateForSplitting = stat;
            sizeCandidateForSplitting = size;
            succsCandidateForSplitting = succCount;
          }
        }
      }
    }

    return candidateForSplitting;
  }

  public static boolean splitIrreducibleNode(Statement statement) {

    Statement splitnode = getCandidateForSplitting(statement);
    if (splitnode == null) {
      return false;
    }

    StatEdge enteredge = splitnode.getPredecessorEdges(StatEdge.TYPE_REGULAR).iterator().next();

    // copy the smallest statement
    Statement splitcopy = copyStatement(splitnode, null, new HashMap<Statement, Statement>());
    initCopiedStatement(splitcopy);

    // insert the copy
    splitcopy.setParent(statement);
    statement.getStats().addWithKey(splitcopy, splitcopy.id);

    // switch input edges
    for (StatEdge prededge : splitnode.getPredecessorEdges(Statement.STATEDGE_DIRECT_ALL)) {
      if (prededge.getSource() == enteredge.getSource() ||
          prededge.closure == enteredge.getSource()) {
        splitnode.removePredecessor(prededge);
        prededge.getSource().changeEdgeNode(Statement.DIRECTION_FORWARD, prededge, splitcopy);
        splitcopy.addPredecessor(prededge);
      }
    }

    // connect successors
    for (StatEdge succ : splitnode.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL)) {
      splitcopy.addSuccessor(new StatEdge(succ.getType(), splitcopy, succ.getDestination(), succ.closure));
    }

    return true;
  }

  private static int getStatementSize(Statement statement) {

    int res = 0;

    if (statement.type == Statement.TYPE_BASICBLOCK) {
      res = ((BasicBlockStatement)statement).getBlock().getSeq().length();
    }
    else {
      for (Statement stat : statement.getStats()) {
        res += getStatementSize(stat);
      }
    }

    return res;
  }

  private static Statement copyStatement(Statement from, Statement to, HashMap<Statement, Statement> mapAltToCopies) {

    if (to == null) {
      // first outer invocation
      to = from.getSimpleCopy();
      mapAltToCopies.put(from, to);
    }

    // copy statements
    for (Statement st : from.getStats()) {
      Statement stcopy = st.getSimpleCopy();

      to.getStats().addWithKey(stcopy, stcopy.id);
      mapAltToCopies.put(st, stcopy);
    }

    // copy edges
    for (int i = 0; i < from.getStats().size(); i++) {
      Statement stold = from.getStats().get(i);
      Statement stnew = to.getStats().get(i);

      for (StatEdge edgeold : stold.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL)) {
        // type cannot be TYPE_EXCEPTION (checked in isIrreducibleTriangle)
        StatEdge edgenew = new StatEdge(edgeold.getType(), stnew,
                                        mapAltToCopies.containsKey(edgeold.getDestination())
                                        ? mapAltToCopies.get(edgeold.getDestination())
                                        : edgeold.getDestination(),
                                        mapAltToCopies.containsKey(edgeold.closure)
                                        ? mapAltToCopies.get(edgeold.closure)
                                        : edgeold.closure);

        stnew.addSuccessor(edgenew);
      }
    }

    // recurse statements
    for (int i = 0; i < from.getStats().size(); i++) {
      Statement stold = from.getStats().get(i);
      Statement stnew = to.getStats().get(i);

      copyStatement(stold, stnew, mapAltToCopies);
    }

    return to;
  }

  private static void initCopiedStatement(Statement statement) {

    statement.initSimpleCopy();
    statement.setCopied(true);

    for (Statement st : statement.getStats()) {
      st.setParent(statement);
      initCopiedStatement(st);
    }
  }
}
