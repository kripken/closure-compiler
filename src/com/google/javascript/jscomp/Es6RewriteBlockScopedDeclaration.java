/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkState;
import static com.google.javascript.jscomp.AstFactory.type;

import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Table;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.colors.StandardColors;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.nullness.Nullable;

/**
 * Rewrite "let"s and "const"s as "var"s. Rename block-scoped declarations and their references when
 * necessary.
 *
 * <p>Note that this must run after Es6RewriteDestructuring, since it does not process destructuring
 * let/const declarations at all.
 *
 * <p>TODO(moz): Try to use MakeDeclaredNamesUnique
 */
public final class Es6RewriteBlockScopedDeclaration extends AbstractPostOrderCallback
    implements CompilerPass {

  private final AbstractCompiler compiler;
  private final AstFactory astFactory;
  private final Table<Node, String, String> renameTable = HashBasedTable.create();
  private final Set<Node> letConsts = new HashSet<>();
  private final Set<String> undeclaredNames = new HashSet<>();
  private final Set<String> externNames = new HashSet<>();
  private static final FeatureSet transpiledFeatures =
      FeatureSet.BARE_MINIMUM.with(Feature.LET_DECLARATIONS, Feature.CONST_DECLARATIONS);
  private final Supplier<String> uniqueNameIdSupplier;
  private final boolean astMayHaveUndeclaredVariables;

  public Es6RewriteBlockScopedDeclaration(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.uniqueNameIdSupplier = compiler.getUniqueNameIdSupplier();
    this.astMayHaveUndeclaredVariables = compiler.getOptions().skipNonTranspilationPasses;
    this.astFactory = compiler.createAstFactory();
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (!n.hasChildren() || !NodeUtil.isBlockScopedDeclaration(n.getFirstChild())) {
      return;
    }
    // NOTE: This pass depends on for-of being transpiled away before it runs.
    checkState(parent == null || !parent.isForOf(), parent);

    if (n.isLet() || n.isConst()) {
      letConsts.add(n);
    }
    if (NodeUtil.isNameDeclaration(n)) {
      for (Node nameNode = n.getFirstChild(); nameNode != null; nameNode = nameNode.getNext()) {
        visitBlockScopedName(t, n, nameNode);
      }
    } else {
      // NOTE: This pass depends on class declarations having been transpiled away
      checkState(n.isFunction() || n.isCatch(), "Unexpected declaration node: %s", n);
      visitBlockScopedName(t, n, n.getFirstChild());
    }
  }

  @Override
  public void process(Node externs, Node root) {
    if (this.astMayHaveUndeclaredVariables) {
      // If we are only transpiling, we may have undefined variables in the code.
      NodeTraversal.traverse(compiler, root, new CollectUndeclaredNames());
    }
    // Record names declared in externs to prevent collisions when declaring vars from let/const.
    this.externNames.addAll(NodeUtil.collectExternVariableNames(compiler, externs));
    NodeTraversal.traverse(compiler, root, this);
    NodeTraversal.traverse(compiler, root, new Es6RenameReferences(renameTable));
    LoopClosureTransformer transformer = new LoopClosureTransformer();
    NodeTraversal.traverse(compiler, root, transformer);
    transformer.transformLoopClosure();
    rewriteDeclsToVars();
    TranspilationPasses.maybeMarkFeaturesAsTranspiledAway(compiler, transpiledFeatures);
  }

  /**
   * Renames block-scoped declarations that shadow a variable in an outer scope
   *
   * <p>Also normalizes declarations with no initializer in a loop to be initialized to undefined.
   */
  private void visitBlockScopedName(NodeTraversal t, Node decl, Node nameNode) {
    Scope scope = t.getScope();
    Node parent = decl.getParent();
    // Normalize "let x;" to "let x = undefined;" if in a loop, since we later convert x
    // to be $jscomp$loop$0.x and want to reset the property to undefined every loop iteration.
    if ((decl.isLet() || decl.isConst())
        && !nameNode.hasChildren()
        && (parent == null || !parent.isForIn())
        && inLoop(decl)) {
      Node undefined = astFactory.createUndefinedValue().srcrefTree(nameNode);
      nameNode.addChildToFront(undefined);
      compiler.reportChangeToEnclosingScope(undefined);
    }

    String oldName = nameNode.getString();
    Scope hoistScope = scope.getClosestHoistScope();
    if (scope != hoistScope) {
      String newName = oldName;
      if (hoistScope.hasSlot(oldName)
          || undeclaredNames.contains(oldName)
          || externNames.contains(oldName)) {
        do {
          newName = oldName + "$" + compiler.getUniqueNameIdSupplier().get();
        } while (hoistScope.hasSlot(newName));
        nameNode.setString(newName);
        compiler.reportChangeToEnclosingScope(nameNode);
        Node scopeRoot = scope.getRootNode();
        renameTable.put(scopeRoot, oldName, newName);
      }
      Var oldVar = scope.getVar(oldName);
      scope.undeclare(oldVar);
      hoistScope.declare(newName, nameNode, oldVar.getInput());
    }
  }

  /**
   * Whether n is inside a loop. If n is inside a function which is inside a loop, we do not
   * consider it to be inside a loop.
   */
  private boolean inLoop(Node n) {
    Node enclosingNode = NodeUtil.getEnclosingNode(n, isLoopOrFunction);
    return enclosingNode != null && !enclosingNode.isFunction();
  }

  private static final Predicate<Node> isLoopOrFunction =
      new Predicate<Node>() {
        @Override
        public boolean apply(Node n) {
          return n.isFunction() || NodeUtil.isLoopStructure(n);
        }
      };

  private static void extractInlineJSDoc(Node srcDeclaration, Node srcName, Node destDeclaration) {
    JSDocInfo existingInfo = srcDeclaration.getJSDocInfo();
    if (existingInfo == null) {
      // Extract inline JSDoc from "src" and add it to the "dest" node.
      existingInfo = srcName.getJSDocInfo();
      srcName.setJSDocInfo(null);
    }
    JSDocInfo.Builder builder = JSDocInfo.Builder.maybeCopyFrom(existingInfo);
    destDeclaration.setJSDocInfo(builder.build());
  }

  private static void maybeAddConstJSDoc(Node srcDeclaration, Node srcName, Node destDeclaration) {
    if (srcDeclaration.isConst()) {
      extractInlineJSDoc(srcDeclaration, srcName, destDeclaration);
      JSDocInfo.Builder builder = JSDocInfo.Builder.maybeCopyFrom(destDeclaration.getJSDocInfo());
      builder.recordConstancy();
      destDeclaration.setJSDocInfo(builder.build());
    }
  }

  private void handleDeclarationList(Node declarationList, Node parent) {
    // Normalize: "const i = 0, j = 0;" becomes "/** @const */ var i = 0; /** @const */ var j = 0;"
    while (declarationList.hasMoreThanOneChild()) {
      Node name = declarationList.getLastChild();
      Node newDeclaration = IR.var(name.detach()).srcref(declarationList);
      maybeAddConstJSDoc(declarationList, name, newDeclaration);
      newDeclaration.insertAfter(declarationList);
      compiler.reportChangeToEnclosingScope(parent);
    }
    maybeAddConstJSDoc(declarationList, declarationList.getFirstChild(), declarationList);
    declarationList.setToken(Token.VAR);
  }

  private void addNodeBeforeLoop(Node newNode, Node loopNode) {
    Node insertSpot = loopNode;
    while (insertSpot.getParent().isLabel()) {
      insertSpot = insertSpot.getParent();
    }
    newNode.insertBefore(insertSpot);
    compiler.reportChangeToEnclosingScope(newNode);
  }

  private void rewriteDeclsToVars() {
    if (!letConsts.isEmpty()) {
      for (Node n : letConsts) {
        if (n.isConst()) {
          handleDeclarationList(n, n.getParent());
        }
        n.setToken(Token.VAR);
        compiler.reportChangeToEnclosingScope(n);
      }
    }
  }

  /**
   * Records undeclared names and aggressively rename possible references to them. Eg: In "{ let
   * inner; } use(inner);", we rename the let declared variable.
   */
  private class CollectUndeclaredNames extends AbstractPostOrderCallback {

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isName() && !t.getScope().hasSlot(n.getString())) {
        undeclaredNames.add(n.getString());
      }
    }
  }

  /** Transforms let/const declarations captured by loop closures. */
  private class LoopClosureTransformer extends AbstractPostOrderCallback {

    private static final String LOOP_OBJECT_NAME = "$jscomp$loop";
    private static final String LOOP_OBJECT_PROPERTY_NAME = "$jscomp$loop$prop$";
    private final Map<Node, LoopObject> loopObjectMap = new LinkedHashMap<>();

    private final SetMultimap<Node, LoopObject> nodesRequiringloopObjectsClosureMap =
        LinkedHashMultimap.create();
    private final SetMultimap<Node, String> nodesHandledForLoopObjectClosure =
        HashMultimap.create();
    private final SetMultimap<Var, Node> referenceMap = LinkedHashMultimap.create();
    // Maps from a var to a unique property name for that var
    // e.g. 'i' -> '$jscomp$loop$prop$i$0'
    private final Map<Var, String> propertyNameMap = new LinkedHashMap<>();

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (!NodeUtil.isReferenceName(n)) {
        return;
      }

      String name = n.getString();
      Scope referencedIn = t.getScope();
      Var var = referencedIn.getVar(name);
      if (var == null) {
        return;
      }

      if (!var.isLet() && !var.isConst()) {
        return;
      }

      if (n.getParent().isLet() || n.getParent().isConst()) {
        letConsts.add(n.getParent());
      }

      // Traverse nodes up from let/const declaration:
      // If we hit a function or the root before a loop - Not a loop closure.
      // if we hit a loop first - maybe loop closure.
      Scope declaredIn = var.getScope();
      Node loopNode = null;
      for (Scope s = declaredIn; ; s = s.getParent()) {
        Node scopeRoot = s.getRootNode();
        if (NodeUtil.isLoopStructure(scopeRoot)) {
          loopNode = scopeRoot;
          break;
        } else if (scopeRoot.hasParent() && NodeUtil.isLoopStructure(scopeRoot.getParent())) {
          loopNode = scopeRoot.getParent();
          break;
        } else if (s.isFunctionBlockScope() || s.isGlobal()) {
          return;
        }
      }

      referenceMap.put(var, n);

      // Traverse scopes from reference scope to declaration scope.
      // If we hit a function - loop closure detected.
      Scope outerMostFunctionScope = null;
      for (Scope s = referencedIn;
          s != declaredIn && s.getRootNode() != loopNode;
          s = s.getParent()) {
        if (s.isFunctionScope()) {
          outerMostFunctionScope = s;
        }
      }

      if (outerMostFunctionScope != null) {
        Node enclosingFunction = outerMostFunctionScope.getRootNode();

        // There are two categories of functions we might find here:
        //  1. a getter or setter in an object literal. We will wrap the entire object literal in
        //     a closure to capture the value of the let/const.
        //  2. a function declaration or expression. We will wrap the function in a closure.
        // (At this point, class methods/getters/setters and object literal member functions are
        // transpiled away.)
        final Node nodeToWrapInClosure;
        if (enclosingFunction.getParent().isGetterDef()
            || enclosingFunction.getParent().isSetterDef()) {
          nodeToWrapInClosure = enclosingFunction.getGrandparent();
          checkState(nodeToWrapInClosure.isObjectLit());
        } else {
          nodeToWrapInClosure = enclosingFunction;
        }
        if (nodesHandledForLoopObjectClosure.containsEntry(nodeToWrapInClosure, name)) {
          return;
        }
        nodesHandledForLoopObjectClosure.put(nodeToWrapInClosure, name);

        LoopObject object =
            loopObjectMap.computeIfAbsent(
                loopNode,
                (Node k) ->
                    new LoopObject(
                        LOOP_OBJECT_NAME + "$" + compiler.getUniqueNameIdSupplier().get()));
        String newPropertyName = createUniquePropertyName(var);
        object.vars.add(var);
        propertyNameMap.put(var, newPropertyName);
        nodesRequiringloopObjectsClosureMap.put(nodeToWrapInClosure, object);
      }
    }

    private String createUniquePropertyName(Var var) {
      return LOOP_OBJECT_PROPERTY_NAME + var.getName() + "$" + uniqueNameIdSupplier.get();
    }

    private void transformLoopClosure() {
      if (loopObjectMap.isEmpty()) {
        return;
      }

      for (Node loopNode : loopObjectMap.keySet()) {
        // Introduce objects to reflect the captured scope variables.
        // Fields are initially left as undefined to avoid cases like:
        //   var $jscomp$loop$0 = {$jscomp$loop$prop$i: 0, $jscomp$loop$prop$j: $jscomp$loop$0.i}
        // They are initialized lazily by changing declarations into assignments
        // later.
        LoopObject loopObject = loopObjectMap.get(loopNode);
        Node objectLitNextIteration = astFactory.createObjectLit();
        for (Var var : loopObject.vars) {
          String newPropertyName = propertyNameMap.get(var);
          objectLitNextIteration.addChildToBack(
              astFactory.createStringKey(
                  newPropertyName,
                  createLoopVarReferenceReplacement(
                      loopObject, var.getNameNode(), newPropertyName)));
        }

        Node updateLoopObject =
            astFactory.createAssign(createLoopObjectNameNode(loopObject), objectLitNextIteration);
        Node objectLit =
            IR.var(createLoopObjectNameNode(loopObject), astFactory.createObjectLit())
                .srcrefTree(loopNode);
        addNodeBeforeLoop(objectLit, loopNode);
        if (loopNode.isVanillaFor()) { // For
          // The initializer is pulled out and placed prior to the loop.
          Node initializer = loopNode.getFirstChild();
          initializer.replaceWith(IR.empty());
          if (!initializer.isEmpty()) {
            if (!NodeUtil.isNameDeclaration(initializer)) {
              initializer = IR.exprResult(initializer).srcref(initializer);
            }
            addNodeBeforeLoop(initializer, loopNode);
          }

          Node increment = loopNode.getChildAtIndex(2);
          if (increment.isEmpty()) {
            increment.replaceWith(updateLoopObject.srcrefTreeIfMissing(loopNode));
          } else {
            Node placeHolder = IR.empty();
            increment.replaceWith(placeHolder);
            placeHolder.replaceWith(
                astFactory.createComma(updateLoopObject, increment).srcrefTreeIfMissing(loopNode));
          }
        } else {
          // We need to make sure the loop object update happens on every loop iteration.
          // We want to keep it at the end of the loop, because that makes it easier to reason
          // about the types.
          //
          // TODO(bradfordcsmith): Maybe move the update to the start of the loop when this pass
          // is moved after the type checking passes.
          //
          // A finally block would do it, but would have more runtime cost, so instead, if we find
          // that there are continue statements referring to the loop we will do this.
          //
          // originalLoopLabel: while (condition) {
          //   $jscomp$loop$0: {
          //      // original loop body here
          //      // with continue statements converted to `break $jscomp$loop$0;`
          //      // If originalLoopLabel exists, we'll also need to traverse into innner loops
          //      // and convert `continue originalLoopLabel;`.
          //   }
          //   $jscomp$loop$0 = { var1: $jscomp$loop$0.var1, var2: $jscomp$loop$0.var2, ... };
          // }
          // We're intentionally using the same name for the inner loop label and the loop variable
          // object. Label names and variables are different namespaces, so they do not conflict.
          String innerBlockLabel = loopObject.name;
          Node loopBody = NodeUtil.getLoopCodeBlock(loopNode);
          if (maybeUpdateContinueStatements(loopNode, innerBlockLabel)) {
            Node innerBlock = IR.block().srcref(loopBody);
            innerBlock.addChildrenToFront(loopBody.removeChildren());
            loopBody.addChildToFront(
                IR.label(IR.labelName(innerBlockLabel).srcref(loopBody), innerBlock)
                    .srcref(loopBody));
          }
          loopBody.addChildToBack(IR.exprResult(updateLoopObject).srcrefTreeIfMissing(loopNode));
        }
        compiler.reportChangeToEnclosingScope(loopNode);

        // For captured variables, change declarations to assignments on the
        // corresponding field of the introduced object. Rename all references
        // accordingly.
        for (Var var : loopObject.vars) {
          String newPropertyName = propertyNameMap.get(var);
          for (Node reference : referenceMap.get(var)) {
            // for-of loops are transpiled away before this pass runs
            checkState(!loopNode.isForOf(), loopNode);
            // For-of and for-in declarations are not altered, since they are
            // used as temporary variables for assignment.
            if (loopNode.isForIn() && loopNode.getFirstChild() == reference.getParent()) {
              // reference is the node loopVar in a for-in or for-of that looks like this:
              // `for (const loopVar of list) {`
              checkState(reference == var.getNameNode(), reference);
              Node referenceParent = reference.getParent();
              checkState(NodeUtil.isNameDeclaration(referenceParent), referenceParent);
              checkState(reference.isName(), reference);
              // Start transpiled form of
              // `for (const p in obj) { ... }`
              // with this statement to copy the loop variable into the corresponding loop object
              // property.
              // `$jscomp$loop$0.$jscomp$loop$prop$0$p = p;`
              Node loopVarReference = reference.cloneNode();
              loopNode
                  .getLastChild()
                  .addChildToFront(
                      IR.exprResult(
                              astFactory.createAssign(
                                  createLoopVarReferenceReplacement(
                                      loopObject, reference, newPropertyName),
                                  loopVarReference))
                          .srcrefTreeIfMissing(reference));
            } else {
              if (NodeUtil.isNameDeclaration(reference.getParent())) {
                Node declaration = reference.getParent();
                Node grandParent = declaration.getParent();
                handleDeclarationList(declaration, grandParent);
                declaration = reference.getParent(); // Might have changed after normalization.
                // Change declaration to assignment, or just drop it if there's
                // no initial value.
                if (reference.hasChildren()) {
                  Node newReference = cloneWithType(reference);
                  Node assign = astFactory.createAssign(newReference, reference.removeFirstChild());
                  extractInlineJSDoc(declaration, reference, declaration);
                  maybeAddConstJSDoc(declaration, reference, declaration);
                  assign.setJSDocInfo(declaration.getJSDocInfo());

                  Node replacement = IR.exprResult(assign).srcrefTreeIfMissing(declaration);
                  declaration.replaceWith(replacement);
                  reference = newReference;
                } else {
                  declaration.detach();
                }
                letConsts.remove(declaration);
                compiler.reportChangeToEnclosingScope(grandParent);
              }

              if (reference.getParent().isCall()
                  && reference.getParent().getFirstChild() == reference) {
                reference.getParent().putBooleanProp(Node.FREE_CALL, false);
              }
              // Change reference to GETPROP.
              Node changeScope = NodeUtil.getEnclosingChangeScopeRoot(reference);
              reference.replaceWith(
                  createLoopVarReferenceReplacement(loopObject, reference, newPropertyName));
              // TODO(johnlenz): Don't work on detached nodes.
              if (changeScope != null) {
                compiler.reportChangeToChangeScope(changeScope);
              }
            }
          }
        }
      }

      // Create wrapper functions and call them.
      for (Node functionOrObjectLit : nodesRequiringloopObjectsClosureMap.keySet()) {
        Node returnNode = IR.returnNode();
        Set<LoopObject> objects = nodesRequiringloopObjectsClosureMap.get(functionOrObjectLit);
        Node[] objectNames = new Node[objects.size()];
        Node[] objectNamesForCall = new Node[objects.size()];
        int i = 0;
        for (LoopObject object : objects) {
          Node paramObjectName = createLoopObjectNameNode(object);
          objectNames[i] = paramObjectName;
          objectNamesForCall[i] = createLoopObjectNameNode(object);
          i++;
        }

        Node iife =
            astFactory.createFunction(
                "",
                IR.paramList(objectNames),
                IR.block(returnNode),
                type(StandardColors.TOP_OBJECT));
        compiler.reportChangeToChangeScope(iife);
        Node call = astFactory.createCall(iife, type(functionOrObjectLit), objectNamesForCall);
        call.putBooleanProp(Node.FREE_CALL, true);
        Node replacement;
        if (NodeUtil.isFunctionDeclaration(functionOrObjectLit)) {
          replacement =
              IR.var(IR.name(functionOrObjectLit.getFirstChild().getString()), call)
                  .srcrefTreeIfMissing(functionOrObjectLit);
        } else {
          replacement = call.srcrefTreeIfMissing(functionOrObjectLit);
        }
        functionOrObjectLit.replaceWith(replacement);
        returnNode.addChildToFront(functionOrObjectLit);
        compiler.reportChangeToEnclosingScope(replacement);
      }
    }

    /**
     * Creates a `$jscomp$loop$0.$jscomp$loop$prop$varName$1` replacement for a reference to
     * `varName`.
     */
    private Node createLoopVarReferenceReplacement(
        LoopObject loopObject, Node reference, String propertyName) {
      Node replacement =
          astFactory.createGetProp(
              createLoopObjectNameNode(loopObject), propertyName, type(reference));
      replacement.srcrefTree(reference);
      return replacement;
    }

    private Node createLoopObjectNameNode(LoopObject loopObject) {
      return astFactory.createName(loopObject.name, type(StandardColors.TOP_OBJECT));
    }

    /**
     * Converts all continue statements referring to the given loop to `break $jscomp$loop$0;` where
     * `$jscomp$loop$0` is the label on the block containing the original loop body.
     *
     * <p>If this method returns {@code true}, then we must wrap the original loop body in a block
     * labeled with the name from the loopObject.
     *
     * @return True if at least one continue statement was found and replaced.
     */
    private boolean maybeUpdateContinueStatements(Node loopNode, String breakLabel) {
      Node loopParent = loopNode.getParent();
      final String originalLoopLabel =
          loopParent.isLabel() ? loopParent.getFirstChild().getString() : null;
      ContinueStatementUpdater continueStatementUpdater =
          new ContinueStatementUpdater(breakLabel, originalLoopLabel);
      NodeTraversal.traverse(
          compiler, NodeUtil.getLoopCodeBlock(loopNode), continueStatementUpdater);
      return continueStatementUpdater.replacedAContinueStatement;
    }

    /**
     * Converts all continue statements referring to the given loop to `break $jscomp$loop$0;` where
     * `$jscomp$loop$0` is the label on the block containing the original loop body.
     */
    private class ContinueStatementUpdater implements NodeTraversal.Callback {

      // label to put on break statements created that replace continue statements.
      private final String breakLabel;
      private final @Nullable String originalLoopLabel;
      // Track how many levels of loops deep we go below this one.

      int loopDepth = 0;
      // Set to true if a continue statement is found
      boolean replacedAContinueStatement = false;

      public ContinueStatementUpdater(String breakLabel, @Nullable String originalLoopLabel) {
        this.breakLabel = breakLabel;
        this.originalLoopLabel = originalLoopLabel;
      }

      @Override
      public boolean shouldTraverse(NodeTraversal nodeTraversal, Node n, Node parent) {
        // NOTE: This pass runs after ES6 classes have already been transpiled away.
        checkState(!n.isClass(), n);
        if (n.isFunction()) {
          return false;
        } else if (NodeUtil.isLoopStructure(n)) {
          if (originalLoopLabel == null) {
            // If this loop has no label, there cannot be any continue statements referring to it
            // in inner loops.
            return false;
          } else {
            loopDepth++;
            return true;
          }
        } else {
          return true;
        }
      }

      @Override
      public void visit(NodeTraversal t, Node n, Node parent) {
        if (NodeUtil.isLoopStructure(n)) {
          loopDepth--;
        } else if (n.isContinue()) {
          if (loopDepth == 0 && !n.hasChildren()) {
            replaceWithBreak(n);
          } else if (originalLoopLabel != null
              && n.hasChildren()
              && originalLoopLabel.equals(n.getOnlyChild().getString())) {
            replaceWithBreak(n);
          } // else continue belongs to some other loop
        } // else nothing to do
      }

      private void replaceWithBreak(Node continueNode) {
        Node labelName = IR.labelName(breakLabel).srcref(continueNode);
        Node breakNode = IR.breakNode(labelName).srcref(continueNode);
        continueNode.replaceWith(breakNode);
        replacedAContinueStatement = true;
      }
    }

    private class LoopObject {

      /**
       * The name of the variable having the loop's internal variables as properties, and the label
       * applied to the block containing the original loop body in cases where these are needed.
       */
      private final String name;

      private final Set<Var> vars = new LinkedHashSet<>();

      private LoopObject(String name) {
        this.name = name;
      }
    }
  }

  private Node cloneWithType(Node node) {
    Node clone = node.cloneNode();
    if (astFactory.isAddingColors()) {
      clone.setColor(node.getColor());
    }
    return clone;
  }
}
