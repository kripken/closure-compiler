/*
 * Copyright 2017 The Closure Compiler Authors.
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
package com.google.javascript.jscomp.ijs;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.NodeUtil;
import com.google.javascript.jscomp.Scope;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import javax.annotation.Nullable;

class PotentialDeclaration {
  // The LHS node of the declaration.
  final Node lhs;
  // The RHS node of the declaration, if it exists.
  final @Nullable
  Node rhs;
  // The scope in which the declaration is defined.
  final @Nullable
  Scope scope;

  PotentialDeclaration(Node lhs, Node rhs, @Nullable Scope scope) {
    this.lhs = lhs;
    this.rhs = rhs;
    this.scope = scope;
  }

  static PotentialDeclaration from(Node nameNode, @Nullable Scope scope) {
    Node rhs = NodeUtil.getRValueOfLValue(nameNode);
    return new PotentialDeclaration(nameNode, rhs, scope);
  }

  Node getStatement() {
    return NodeUtil.getEnclosingStatement(lhs);
  }

  JSDocInfo getJsDoc() {
    return NodeUtil.getBestJSDocInfo(lhs);
  }

  /**
   * Remove this "potential declaration" completely.
   * Usually, this is because the same symbol has already been declared in this file.
   */
  void remove(AbstractCompiler compiler) {
    Node statement = getStatement();
    NodeUtil.deleteNode(statement, compiler);
    statement.removeChildren();
  }

  void removeStringKeyValue(Node stringKey) {
    Node value = stringKey.getOnlyChild();
    Node replacementValue = IR.number(0).srcrefTree(value);
    stringKey.replaceChild(value, replacementValue);
  }

  /**
   * Simplify this declaration to only include what's necessary for typing.
   * Usually, this means removing the RHS and leaving a type annotation.
   */
  void simplify(AbstractCompiler compiler) {
    Node nameNode = lhs;
    JSDocInfo jsdoc = getJsDoc();
    if (jsdoc != null && jsdoc.hasEnumParameterType()) {
      // Remove values from enums
      if (rhs.isObjectLit() && rhs.hasChildren()) {
        for (Node key : rhs.children()) {
          removeStringKeyValue(key);
        }
        compiler.reportChangeToEnclosingScope(rhs);
      }
      return;
    }
    if (NodeUtil.isNamespaceDecl(nameNode)) {
      Node objLit = rhs;
      if (rhs.isOr()) {
        objLit = rhs.getLastChild().detach();
        rhs.replaceWith(objLit);
        compiler.reportChangeToEnclosingScope(nameNode);
      }
      if (objLit.hasChildren()) {
        for (Node key : objLit.children()) {
          if (!isTypedRhs(key.getLastChild())) {
            removeStringKeyValue(key);
            JsdocUtil.updateJsdoc(compiler, key);
            compiler.reportChangeToEnclosingScope(key);
          }
        }
      }
      return;
    }
    if (nameNode.matchesQualifiedName("exports")) {
      // Replace the RHS of a default goog.module export with Unknown
      replaceRhsWithUnknown(rhs);
      compiler.reportChangeToEnclosingScope(nameNode);
      return;
    }
    // Just completely remove the RHS, and replace with a getprop.
    Node newStatement =
        NodeUtil.newQNameDeclaration(compiler, nameNode.getQualifiedName(), null, jsdoc);
    newStatement.useSourceInfoIfMissingFromForTree(nameNode);
    Node oldStatement = getStatement();
    NodeUtil.deleteChildren(oldStatement, compiler);
    oldStatement.replaceWith(newStatement);
    compiler.reportChangeToEnclosingScope(newStatement);
  }

  static boolean isTypedRhs(Node rhs) {
    return rhs.isFunction()
        || rhs.isClass()
        || (rhs.isQualifiedName() && rhs.matchesQualifiedName("goog.abstractMethod"))
        || (rhs.isQualifiedName() && rhs.matchesQualifiedName("goog.nullFunction"));
  }

  private static void replaceRhsWithUnknown(Node rhs) {
    rhs.replaceWith(IR.cast(IR.number(0), JsdocUtil.getQmarkTypeJSDoc()).srcrefTree(rhs));
  }

}
