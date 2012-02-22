/*
 * Copyright 2003-2012 Dave Griffith, Bas Leijdekkers
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
package com.siyeh.ig.performance;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StringConcatenationInsideStringBufferAppendInspection extends BaseInspection {

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("string.concatenation.inside.string.buffer.append.display.name");
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @Override
  @NotNull
  public String buildErrorString(Object... infos) {
    final PsiClass aClass = (PsiClass)infos[0];
    final String className = aClass.getName();
    return InspectionGadgetsBundle.message("string.concatenation.inside.string.buffer.append.problem.descriptor", className);
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new StringConcatenationInsideStringBufferAppendVisitor();
  }

  @Override
  public InspectionGadgetsFix buildFix(Object... infos) {
    return new ReplaceWithChainedAppendFix();
  }

  private static class ReplaceWithChainedAppendFix extends InspectionGadgetsFix {

    @NotNull
    public String getName() {
      return InspectionGadgetsBundle.message(
        "string.concatenation.inside.string.buffer.append.replace.quickfix");
    }

    @Override
    public void doFix(Project project, ProblemDescriptor descriptor) throws IncorrectOperationException {
      final PsiElement methodNameElement = descriptor.getPsiElement();
      final PsiReferenceExpression methodExpression = (PsiReferenceExpression)methodNameElement.getParent();
      if (methodExpression == null) {
        return;
      }
      final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)methodExpression.getParent();
      if (methodCallExpression == null) {
        return;
      }
      final PsiMethod method = methodCallExpression.resolveMethod();
      if (method == null) {
        return;
      }
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass == null) {
        return;
      }
      final String qualifiedName = containingClass.getQualifiedName();
      if (qualifiedName == null) {
        return;
      }
      final PsiExpression qualifier = methodExpression.getQualifierExpression();
      if (qualifier == null) {
        return;
      }
      final PsiExpressionList argumentList = methodCallExpression.getArgumentList();
      final PsiExpression[] arguments = argumentList.getExpressions();
      final PsiExpression argument = arguments[0];
      final boolean useStringValueOf;
      useStringValueOf = !qualifiedName.equals(CommonClassNames.JAVA_LANG_STRING_BUFFER) &&
                         !qualifiedName.equals(CommonClassNames.JAVA_LANG_STRING_BUILDER);
      @NonNls final StringBuilder newExpressionBuffer =
        buildAppendExpression(argument, useStringValueOf, new StringBuilder(qualifier.getText()));
      if (newExpressionBuffer == null) {
        return;
      }
      replaceExpression(methodCallExpression, newExpressionBuffer.toString());
    }

    @Nullable
    private static StringBuilder buildAppendExpression(PsiExpression concatenation, boolean useStringValueOf, @NonNls StringBuilder out)
      throws IncorrectOperationException {
      final PsiType type = concatenation.getType();
      if (type == null) {
        return null;
      }
      if (concatenation instanceof PsiPolyadicExpression && type.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
        PsiPolyadicExpression polyadicExpression = (PsiPolyadicExpression)concatenation;
        final PsiExpression[] operands = polyadicExpression.getOperands();
        boolean isConstant = true;
        boolean isString = false;
        final StringBuilder builder = new StringBuilder();
        for (PsiExpression operand : operands) {
          if (isConstant && PsiUtil.isConstantExpression(operand)) {
            if (builder.length() != 0) {
              builder.append('+');
            }
            final PsiType operandType = operand.getType();
            if (operandType != null && operandType.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
              isString = true;
            }
            builder.append(operand.getText());
          }
          else {
            isConstant = false;
            if (builder.length() != 0) {
              append(builder, useStringValueOf && !isString, out);
              builder.setLength(0);
            }
            buildAppendExpression(operand, useStringValueOf, out);
          }
        }
        if (builder.length() != 0) {
          append(builder, false, out);
        }
      }
      else if (concatenation instanceof PsiParenthesizedExpression) {
        final PsiParenthesizedExpression parenthesizedExpression = (PsiParenthesizedExpression)concatenation;
        final PsiExpression expression = parenthesizedExpression.getExpression();
        if (expression != null) {
          return buildAppendExpression(expression, useStringValueOf, out);
        }
      }
      else {
        append(concatenation.getText(), useStringValueOf && !type.equalsToText(CommonClassNames.JAVA_LANG_STRING), out);
      }
      return out;
    }

    private static void append(CharSequence text, boolean useStringValueOf, StringBuilder out) {
      out.append(".append(");
      if (useStringValueOf) {
        out.append("String.valueOf(").append(text).append(')');
      }
      else {
        out.append(text);
      }
      out.append(')');
    }
  }

  private static class StringConcatenationInsideStringBufferAppendVisitor extends BaseInspectionVisitor {

    @Override
    public void visitMethodCallExpression(PsiMethodCallExpression expression) {
      super.visitMethodCallExpression(expression);
      final PsiReferenceExpression methodExpression = expression.getMethodExpression();
      @NonNls final String methodName = methodExpression.getReferenceName();
      if (!"append".equals(methodName)) {
        return;
      }
      final PsiExpressionList argumentList = expression.getArgumentList();
      final PsiExpression[] arguments = argumentList.getExpressions();
      if (arguments.length != 1) {
        return;
      }
      final PsiExpression argument = arguments[0];
      if (!isConcatenation(argument)) {
        return;
      }
      final PsiMethod method = expression.resolveMethod();
      if (method == null) {
        return;
      }
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass == null) {
        return;
      }
      final String className = containingClass.getQualifiedName();
      if (CommonClassNames.JAVA_LANG_STRING_BUFFER.equals(className) ||
          CommonClassNames.JAVA_LANG_STRING_BUILDER.equals(className)) {
        registerMethodCallError(expression, containingClass);
        return;
      }
      final Project project = containingClass.getProject();
      final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
      final PsiClass appendableClass = psiFacade.findClass("java.lang.Appendable", GlobalSearchScope.allScope(project));
      if (appendableClass == null) {
        return;
      }
      if (!InheritanceUtil.isInheritorOrSelf(containingClass, appendableClass, true)) {
        return;
      }
      registerMethodCallError(expression, containingClass);
    }

    private static boolean isConcatenation(PsiExpression expression) {
      if (expression instanceof PsiParenthesizedExpression) {
        final PsiParenthesizedExpression parenthesizedExpression = (PsiParenthesizedExpression)expression;
        return isConcatenation(parenthesizedExpression.getExpression());
      }
      if (!(expression instanceof PsiPolyadicExpression)) {
        return false;
      }
      if (PsiUtil.isConstantExpression(expression)) {
        return false;
      }
      final PsiType type = expression.getType();
      if (type == null) {
        return false;
      }
      return type.equalsToText(CommonClassNames.JAVA_LANG_STRING);
    }
  }
}