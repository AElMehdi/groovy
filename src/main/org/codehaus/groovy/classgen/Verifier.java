/*
 $Id$

 Copyright 2003 (C) James Strachan and Bob Mcwhirter. All Rights Reserved.

 Redistribution and use of this software and associated documentation
 ("Software"), with or without modification, are permitted provided
 that the following conditions are met:

 1. Redistributions of source code must retain copyright
    statements and notices.  Redistributions must also contain a
    copy of this document.

 2. Redistributions in binary form must reproduce the
    above copyright notice, this list of conditions and the
    following disclaimer in the documentation and/or other
    materials provided with the distribution.

 3. The name "groovy" must not be used to endorse or promote
    products derived from this Software without prior written
    permission of The Codehaus.  For written permission,
    please contact info@codehaus.org.

 4. Products derived from this Software may not be called "groovy"
    nor may "groovy" appear in their names without prior written
    permission of The Codehaus. "groovy" is a registered
    trademark of The Codehaus.

 5. Due credit should be given to The Codehaus -
    http://groovy.codehaus.org/

 THIS SOFTWARE IS PROVIDED BY THE CODEHAUS AND CONTRIBUTORS
 ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT
 NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL
 THE CODEHAUS OR ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 OF THE POSSIBILITY OF SUCH DAMAGE.

 */
package org.codehaus.groovy.classgen;

import groovy.lang.GroovyObject;
import groovy.lang.MetaClass;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.codehaus.groovy.ast.ArgumentListExpression;
import org.codehaus.groovy.ast.BinaryExpression;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.Expression;
import org.codehaus.groovy.ast.ExpressionStatement;
import org.codehaus.groovy.ast.FieldExpression;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GroovyClassVisitor;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodCallExpression;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.ReturnStatement;
import org.codehaus.groovy.ast.Statement;
import org.codehaus.groovy.ast.StatementBlock;
import org.codehaus.groovy.ast.StaticMethodCallExpression;
import org.codehaus.groovy.ast.VariableExpression;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.syntax.Token;
import org.objectweb.asm.Constants;

/**
 * Verifies the AST node and adds any defaulted AST code before
 * bytecode generation occurs.
 * 
 * @author <a href="mailto:james@coredevelopers.net">James Strachan</a>
 * @version $Revision$
 */
public class Verifier implements GroovyClassVisitor, Constants {

    public void visitClass(ClassNode node) {
        node.addInterface(GroovyObject.class.getName());

        // lets add a new field for the metaclass
        FieldNode metaClassField =
            node.addField(
                "metaClass",
                ACC_PUBLIC | ACC_FINAL,
                MetaClass.class.getName(),
                new StaticMethodCallExpression(
                    InvokerHelper.class.getName(),
                    "getMetaClass",
                    new VariableExpression("this")));

        // lets add the invokeMethod implementation
        boolean addDelegateObject = node instanceof InnerClassNode && node.getSuperClass().equals("groovy/lang/Closure");
        if (addDelegateObject) {
            // don't do anything as the base class implements the invokeMethod
        }
        else {
            node.addMethod(
                "invokeMethod",
                ACC_PUBLIC,
                Object.class.getName(),
                new Parameter[] {
                    new Parameter(String.class.getName(), "name"),
                    new Parameter(Object.class.getName(), "arguments")},
                new ReturnStatement(
                    new MethodCallExpression(
                        new FieldExpression(metaClassField),
                        "invokeMethod",
                        new ArgumentListExpression(
                            new Expression[] {
                                new VariableExpression("this"),
                                new VariableExpression("name"),
                                new VariableExpression("arguments")}))));
        }

        if (node.getConstructors().isEmpty()) {
            node.addConstructor(new ConstructorNode(ACC_PUBLIC, null));
        }

        node.addMethod(
            "getMetaClass",
            ACC_PUBLIC,
            MetaClass.class.getName(),
            Parameter.EMPTY_ARRAY,
            new ReturnStatement(new FieldExpression(metaClassField)));

        addFieldInitialization(node);
    }

    protected void addClosureCode(InnerClassNode node) {
        // add a new invoke
    }

    protected void addFieldInitialization(ClassNode node) {
        for (Iterator iter = node.getConstructors().iterator(); iter.hasNext();) {
            addFieldInitialization(node, (ConstructorNode) iter.next());
        }
    }

    protected void addFieldInitialization(ClassNode node, ConstructorNode constructorNode) {
        List statements = new ArrayList();
        for (Iterator iter = node.getFields().iterator(); iter.hasNext();) {
            addFieldInitialization(statements, constructorNode, (FieldNode) iter.next());
        }
        if (!statements.isEmpty()) {
            Statement code = constructorNode.getCode();
            List otherStatements = new ArrayList();
            if (code instanceof StatementBlock) {
                StatementBlock block = (StatementBlock) code;
                otherStatements.addAll(block.getStatements());
            }
            else if (code != null) {
                otherStatements.add(code);
            }
            if (! otherStatements.isEmpty()) {
                Statement first = (Statement) otherStatements.get(0);
                if (isSuperMethodCall(first)) {
                    otherStatements.remove(0);
                    statements.add(0, first);
                }
                statements.addAll(otherStatements);
            }
            constructorNode.setCode(new StatementBlock(statements));
        }
    }

    protected boolean isSuperMethodCall(Statement first) {
        if (first instanceof ExpressionStatement) {
            ExpressionStatement exprStmt = (ExpressionStatement) first;
            Expression expr = exprStmt.getExpression();
            if (expr instanceof MethodCallExpression) {
                return MethodCallExpression.isSuperMethodCall((MethodCallExpression) expr);
            }
        }
        return false;
    }

    protected void addFieldInitialization(List list, ConstructorNode constructorNode, FieldNode fieldNode) {
        Expression expression = fieldNode.getInitialValueExpression();
        if (expression != null) {
            list.add(
                new ExpressionStatement(
                    new BinaryExpression(
                        new FieldExpression(fieldNode),
                        Token.equal(fieldNode.getLineNumber(), fieldNode.getColumnNumber()),
                        expression)));
        }
    }

    public void visitConstructor(ConstructorNode node) {
    }

    public void visitMethod(MethodNode node) {
    }

    public void visitField(FieldNode node) {
    }

    public void visitProperty(PropertyNode node) {
    }

}
