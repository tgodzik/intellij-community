// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.hint.api.impls;

import com.intellij.codeInsight.AnnotationTargetUtil;
import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.completion.CompletionMemory;
import com.intellij.codeInsight.completion.JavaCompletionUtil;
import com.intellij.codeInsight.completion.JavaMethodCallElement;
import com.intellij.codeInsight.daemon.impl.ParameterHintsPresentationManager;
import com.intellij.codeInsight.hint.ParameterInfoController;
import com.intellij.codeInsight.hints.ParameterHintsPass;
import com.intellij.codeInsight.javadoc.JavaDocInfoGenerator;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.lang.parameterInfo.*;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiImplUtil;
import com.intellij.psi.impl.source.resolve.CompletionParameterTypeInferencePolicy;
import com.intellij.psi.impl.source.resolve.DefaultParameterTypeInferencePolicy;
import com.intellij.psi.infos.CandidateInfo;
import com.intellij.psi.infos.MethodCandidateInfo;
import com.intellij.psi.scope.MethodProcessorSetupFailedException;
import com.intellij.psi.scope.PsiConflictResolver;
import com.intellij.psi.scope.processor.MethodCandidatesProcessor;
import com.intellij.psi.scope.processor.MethodResolverProcessor;
import com.intellij.psi.scope.util.PsiScopesUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.DocumentUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.List;
import java.util.*;

/**
 * @author Maxim.Mossienko
 */
public class MethodParameterInfoHandler implements ParameterInfoHandlerWithTabActionSupport<PsiExpressionList, Object, PsiExpression>, DumbAware {
  private static final Set<Class> ourArgumentListAllowedParentClassesSet = ContainerUtil.newHashSet(
    PsiMethodCallExpression.class, PsiNewExpression.class, PsiAnonymousClass.class, PsiEnumConstant.class);
  private static final Set<Class> ourStopSearch = Collections.singleton(PsiMethod.class);
  private static final String WHITESPACE_OR_LINE_BREAKS = " \t\n";
  private static final Key<Inlay> CURRENT_HINT = Key.create("current.hint");
  private static final Key<List<Inlay>> HIGHLIGHTED_HINTS = Key.create("highlighted.hints");

  @Override
  public Object[] getParametersForLookup(LookupElement item, ParameterInfoContext context) {
    final List<? extends PsiElement> elements = JavaCompletionUtil.getAllPsiElements(item);
    return elements != null && !elements.isEmpty() && elements.get(0) instanceof PsiMethod ? elements.toArray() : null;
  }

  @Override
  public boolean couldShowInLookup() {
    return true;
  }

  @Override
  @Nullable
  public PsiExpressionList findElementForParameterInfo(@NotNull final CreateParameterInfoContext context) {
    PsiExpressionList argumentList = findArgumentList(context.getFile(), context.getOffset(), context.getParameterListStart(), true);

    if (argumentList != null) {
      return findMethodsForArgumentList(context, argumentList);
    }
    return null;
  }

  private PsiExpressionList findArgumentList(final PsiFile file, int offset, int parameterStart, boolean allowOuter) {
    PsiExpressionList argumentList = ParameterInfoUtils.findArgumentList(file, offset, parameterStart, this, allowOuter);
    if (argumentList == null && allowOuter) {
      PsiCall call = ParameterInfoUtils.findParentOfTypeWithStopElements(file, offset, PsiMethodCallExpression.class, PsiMethod.class);
      if (call == null) {
        call = ParameterInfoUtils.findParentOfTypeWithStopElements(file, offset, PsiNewExpression.class, PsiMethod.class);
      }
      if (call != null) {
        argumentList = call.getArgumentList();
        if (argumentList != null && !argumentList.getTextRange().containsOffset(offset)) {
          if (PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiParenthesizedExpression.class, false, PsiCall.class) != null) {
            return null;
          }
        }
      }
    }
    return argumentList;
  }

  private static PsiExpressionList findMethodsForArgumentList(final CreateParameterInfoContext context,
                                                              @NotNull final PsiExpressionList argumentList) {

    CandidateInfo[] candidates = getMethods(argumentList);
    if (candidates.length == 0) {
      return null;
    }
    context.setItemsToShow(candidates);
    return argumentList;
  }

  @Override
  public void showParameterInfo(@NotNull final PsiExpressionList element, @NotNull final CreateParameterInfoContext context) {
    int offset = element.getTextRange().getStartOffset();
    if (CodeInsightSettings.getInstance().SHOW_PARAMETER_NAME_HINTS_ON_COMPLETION) {
      ParameterInfoController controller = ParameterInfoController.findControllerAtOffset(context.getEditor(), offset);
      PsiElement parent = element.getParent();
      if (parent instanceof PsiCall && controller != null && controller.isHintShown(false)) {
        Object highlighted = controller.getHighlighted();
        Object[] objects = controller.getObjects();
        if (objects != null && objects.length > 0 && (highlighted != null || objects.length == 1)) {
          PsiCall methodCall = (PsiCall)parent;
          JavaMethodCallElement.setCompletionModeIfNotSet(methodCall, controller);
          PsiMethod targetMethod = (PsiMethod)((CandidateInfo)(highlighted == null ? objects[0] : highlighted)).getElement();
          CompletionMemory.registerChosenMethod(targetMethod, methodCall);
          controller.setPreservedOnHintHidden(true);
          ParameterHintsPass.syncUpdate(methodCall, context.getEditor());
        }
      }
    }
    context.showHint(element, offset, this);
  }

  @Override
  public PsiExpressionList findElementForUpdatingParameterInfo(@NotNull final UpdateParameterInfoContext context) {
    if (context.isPreservedOnHintHidden() && isOutsideOfCompletedInvocation(context)) {
      context.setPreservedOnHintHidden(false);
      return null;
    }
    PsiExpressionList expressionList = findArgumentList(context.getFile(), context.getOffset(), context.getParameterListStart(),
                                                        !context.isSingleParameterInfo());
    if (expressionList != null) {
      Object[] candidates = context.getObjectsToView();
      if (candidates != null && candidates.length != 0) {
        Object currentMethodInfo = context.getHighlightedParameter();
        if (currentMethodInfo == null) currentMethodInfo = candidates[0];
        PsiElement element = currentMethodInfo instanceof CandidateInfo ? ((CandidateInfo)currentMethodInfo).getElement() :
                             currentMethodInfo instanceof PsiElement ? (PsiElement) currentMethodInfo :
                             null;
        if ((element instanceof PsiMethod)) {
          PsiMethod method = (PsiMethod)element;
          PsiElement parent = expressionList.getParent();

          String originalMethodName = method.getName();
          PsiQualifiedReference currentMethodReference = null;
          if (parent instanceof PsiMethodCallExpression && !method.isConstructor()) {
            currentMethodReference = ((PsiMethodCallExpression)parent).getMethodExpression();
          }
          else if (parent instanceof PsiNewExpression) {
            currentMethodReference = ((PsiNewExpression)parent).getClassReference();
          }
          else if (parent instanceof PsiAnonymousClass) {
            currentMethodReference = ((PsiAnonymousClass)parent).getBaseClassReference();
          }
          if (currentMethodReference == null || originalMethodName.equals(currentMethodReference.getReferenceName())) {

            int currentNumberOfParameters = expressionList.getExpressionCount();
            PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(context.getProject());
            Document document = psiDocumentManager.getCachedDocument(context.getFile());
            if (parent instanceof PsiCallExpression && JavaMethodCallElement.isCompletionMode((PsiCall)parent)) {
              PsiMethod chosenMethod = CompletionMemory.getChosenMethod((PsiCall)parent);
              if ((context.getHighlightedParameter() != null || candidates.length == 1) && chosenMethod != null &&
                  document != null && psiDocumentManager.isCommitted(document) &&
                  isIncompatibleParameterCount(chosenMethod, currentNumberOfParameters)) {
                JavaMethodCallElement.setCompletionMode((PsiCall)parent, false);
                ParameterHintsPass.syncUpdate(parent, context.getEditor()); // make sure the statement above takes effect
                highlightHints(context.getEditor(), null, -1, context.getCustomContext());
              }
              else {
                int index = ParameterInfoUtils.getCurrentParameterIndex(expressionList.getNode(),
                                                                        context.getOffset(), JavaTokenType.COMMA);
                TextRange textRange = expressionList.getTextRange();
                if (context.getOffset() <= textRange.getStartOffset() || context.getOffset() >= textRange.getEndOffset()) index = -1;
                highlightHints(context.getEditor(), expressionList, context.isInnermostContext() ? index : -1, context.getCustomContext());
              }
            }

            return expressionList;
          }
        }
      }
    }
    highlightHints(context.getEditor(), null, -1, context.getCustomContext());
    return null;
  }

  private static boolean isOutsideOfCompletedInvocation(UpdateParameterInfoContext context) {
    PsiElement owner = context.getParameterOwner();
    if (owner != null && owner.isValid()) {
      TextRange ownerTextRange = getRelatedRange(owner, context.getEditor());
      int caretOffset = context.getOffset();
      if (ownerTextRange != null) {
        if (caretOffset >= ownerTextRange.getStartOffset() && caretOffset <= ownerTextRange.getEndOffset()) {
          return false;
        }
        else {
          for (PsiElement element = owner.getFirstChild(); element != null; element = element.getNextSibling()) {
            if (element instanceof PsiErrorElement) return false;
          }
          PsiElement parent = owner.getParent();
          if (owner instanceof PsiExpressionList && parent instanceof PsiCall) {
            PsiMethod chosenMethod = CompletionMemory.getChosenMethod((PsiCall)parent);
            if (chosenMethod != null) {
              int parametersCount = chosenMethod.getParameterList().getParametersCount();
              boolean varArgs = chosenMethod.isVarArgs();
              if (Registry.is("editor.completion.hints.virtual.comma")) {
                int requiredParameters = varArgs ? parametersCount - 1 : parametersCount;
                int actualParameters = ((PsiExpressionList)owner).getExpressionCount();
                if (actualParameters < requiredParameters && ((PsiCall)parent).resolveMethod() == null) return false;
              }
              else if (((PsiExpressionList)owner).isEmpty() &&
                       (parametersCount == 1 && !varArgs || parametersCount == 2 && varArgs) &&
                       !overloadWithNoParametersExists(chosenMethod, context.getObjectsToView())) {
                  return false;
                }
              }
            }
          }
        }
      }
    return true;
  }

  private static TextRange getRelatedRange(PsiElement owner, Editor editor) {
    TextRange range = owner.getTextRange();
    if (range == null) return null;
    Document document = editor.getDocument();
    if (Registry.is("editor.keep.completion.hints.even.longer")) {
      int startY = editor.visualPositionToXY(editor.offsetToVisualPosition(range.getStartOffset())).y;
      int endY = editor.visualPositionToXY(editor.offsetToVisualPosition(range.getEndOffset())).y;
      Rectangle visibleArea = editor.getScrollingModel().getVisibleArea();
      return startY > visibleArea.getMaxY() || endY < visibleArea.getMinY() ? null : new TextRange(0, document.getTextLength());
    }
    if (!Registry.is("editor.keep.completion.hints.longer")) return range;
    return new TextRange(DocumentUtil.getLineStartOffset(range.getStartOffset(), document),
                         DocumentUtil.getLineEndOffset(range.getEndOffset(), document));
  }

  private static boolean overloadWithNoParametersExists(PsiMethod method, Object[] candidates) {
    String methodName = method.getName();
    return ContainerUtil.find(candidates, c -> {
      if (!(c instanceof CandidateInfo)) return false;
      PsiElement e = ((CandidateInfo)c).getElement();
      if (!(e instanceof PsiMethod)) return false;
      PsiMethod m = (PsiMethod)e;
      return m.getParameterList().isEmpty() && m.getName().equals(methodName);
    }) != null;
  }

  private static boolean isIncompatibleParameterCount(@NotNull PsiMethod method, int numberOfParameters) {
    int limit = JavaMethodCallElement.getCompletionHintsLimit();
    int originalNumberOfParameters = method.getParameterList().getParametersCount();
    return Registry.is("editor.completion.hints.virtual.comma")
           ? !PsiImplUtil.isVarArgs(method) && numberOfParameters > originalNumberOfParameters
           : PsiImplUtil.isVarArgs(method)
             ? originalNumberOfParameters > 2 &&
               numberOfParameters < Math.min(limit, originalNumberOfParameters) - 1 && !(limit == 1 && numberOfParameters == 0)
             : (originalNumberOfParameters < numberOfParameters || numberOfParameters < Math.min(limit, originalNumberOfParameters)) &&
               !(Math.min(limit, originalNumberOfParameters) == 1 && numberOfParameters == 0);
  }

  @Override
  public void updateParameterInfo(@NotNull final PsiExpressionList o, @NotNull final UpdateParameterInfoContext context) {
    int offset = context.getOffset();
    TextRange elRange = o.getTextRange();
    int index = offset <= elRange.getStartOffset() || offset >= elRange.getEndOffset()
                ? -1 : ParameterInfoUtils.getCurrentParameterIndex(o.getNode(), offset, JavaTokenType.COMMA);
    context.setCurrentParameter(index);

    Object[] candidates = context.getObjectsToView();
    PsiExpression[] args = o.getExpressions();
    PsiCall call = getCall(o);
    PsiElement realResolve = call != null ? call.resolveMethod() : null;

    PsiMethod chosenMethod = CompletionMemory.getChosenMethod(call);
    CandidateInfo chosenInfo = null;
    CandidateInfo completeMatch = null;

    for (int i = 0; i < candidates.length; i++) {
      CandidateInfo candidate = (CandidateInfo)candidates[i];
      PsiMethod method = (PsiMethod)candidate.getElement();
      if (!method.isValid()) continue;
      if (candidate instanceof MethodCandidateInfo && !((MethodCandidateInfo)candidate).getSiteSubstitutor().isValid()) continue;
      PsiSubstitutor substitutor = getCandidateInfoSubstitutor(o, candidate, method == realResolve);
      assert substitutor != null;

      if (!method.isValid() || !substitutor.isValid()) {
          // this may sometimes happen e,g, when editing method call in field initializer candidates in the same file get invalidated
        context.setUIComponentEnabled(i, false);
        continue;
      }

      PsiParameter[] parms = method.getParameterList().getParameters();
      boolean enabled = true;
      if (parms.length <= index) {
        if (parms.length > 0) {
          if (method.isVarArgs()) {
            for (int j = 0; j < parms.length - 1; j++) {
              PsiType parmType = substitutor.substitute(parms[j].getType());
              PsiType argType = args[j].getType();
              if (argType != null && !parmType.isAssignableFrom(argType)) {
                enabled = false;
                break;
              }
            }

            if (enabled) {
              PsiArrayType lastParmType = (PsiArrayType)substitutor.substitute(parms[parms.length - 1].getType());
              PsiType componentType = lastParmType.getComponentType();

              if (parms.length == args.length) {
                PsiType lastArgType = args[args.length - 1].getType();
                if (lastArgType != null && !lastParmType.isAssignableFrom(lastArgType) &&
                    !componentType.isAssignableFrom(lastArgType)) {
                  enabled = false;
                }
              }
              else {
                for (int j = parms.length; j <= index && j < args.length; j++) {
                  PsiExpression arg = args[j];
                  PsiType argType = arg.getType();
                  if (argType != null && !componentType.isAssignableFrom(argType)) {
                    enabled = false;
                    break;
                  }
                }
              }
            }
          }
          else {
            enabled = false;
          }
        }
        else {
          enabled = index == 0;
        }
      }
      else {
        enabled = isAssignableParametersBeforeGivenIndex(parms, args, index, substitutor);
      }

      context.setUIComponentEnabled(i, enabled);
      if (candidates.length > 1 && enabled) {
        if (PsiManager.getInstance(context.getProject()).areElementsEquivalent(chosenMethod, method)) {
          chosenInfo = candidate;
        }

        if (parms.length == args.length && realResolve == method &&
            isAssignableParametersBeforeGivenIndex(parms, args, args.length, substitutor)) {
          completeMatch = candidate;
        }
      }
    }

    CandidateInfo parameterToHighlight = chosenInfo;
    if (completeMatch != null &&
        (chosenInfo == null || (args.length > 0 && !(context.isPreservedOnHintHidden() &&
                                                     Registry.is("editor.completion.hints.virtual.comma") &&
                                                     getParameterCount(completeMatch) < getParameterCount(chosenInfo))))) {
      parameterToHighlight = completeMatch;
    }
    context.setHighlightedParameter(parameterToHighlight);

    Object highlightedCandidate = candidates.length == 1 ? candidates[0] : context.getHighlightedParameter();
    if (highlightedCandidate != null) {
      PsiMethod method = (PsiMethod)(highlightedCandidate instanceof CandidateInfo
                                     ? ((CandidateInfo)highlightedCandidate).getElement() : highlightedCandidate);
      if (!method.isVarArgs() && index > 0 && index >= method.getParameterList().getParametersCount()) context.setCurrentParameter(-1);
    }
  }

  private static int getParameterCount(@NotNull CandidateInfo info) {
    return ((PsiMethod)info.getElement()).getParameterList().getParametersCount();
  }

  private static void highlightHints(@NotNull Editor editor, @Nullable PsiExpressionList expressionList, int currentHintIndex,
                                     @NotNull UserDataHolder context) {
    if (editor.isDisposed() || editor instanceof EditorWindow) return;
    ParameterHintsPresentationManager presentationManager = ParameterHintsPresentationManager.getInstance();
    Inlay currentHint = null;
    List<Inlay> highlightedHints = null;
    if (expressionList != null && expressionList.isValid()) {
      int expressionCount = expressionList.getExpressionCount();
      if (currentHintIndex == 0 || currentHintIndex > 0 && currentHintIndex < expressionCount) {
        highlightedHints = new ArrayList<>(expressionCount);
        ParameterHintsPass.syncUpdate(expressionList.getParent(), editor);
        PsiElement prevDelimiter, nextDelimiter;
        for (int i = 0; i < Math.max(expressionCount, currentHintIndex == 0 ? 1 : 0); i++) {
          if (i < expressionCount) {
            PsiExpression expression = expressionList.getExpressions()[i];
            //noinspection StatementWithEmptyBody
            for (prevDelimiter = expression;
                 prevDelimiter != null && !(prevDelimiter instanceof PsiJavaToken);
                 prevDelimiter = prevDelimiter.getPrevSibling())
              ;
            //noinspection StatementWithEmptyBody
            for (nextDelimiter = expression;
                 nextDelimiter != null && !(nextDelimiter instanceof PsiJavaToken);
                 nextDelimiter = nextDelimiter.getNextSibling())
              ;
          }
          else {
            prevDelimiter = expressionList.getFirstChild(); // left parenthesis
            nextDelimiter = expressionList.getLastChild(); // right parenthesis
          }
          if (prevDelimiter != null && nextDelimiter != null) {
            CharSequence text = editor.getDocument().getImmutableCharSequence();
            int firstRangeStartOffset = prevDelimiter.getTextRange().getEndOffset();
            int firstRangeEndOffset = CharArrayUtil.shiftForward(text, firstRangeStartOffset, WHITESPACE_OR_LINE_BREAKS);
            for (Inlay inlay : presentationManager.getParameterHintsInRange(editor, firstRangeStartOffset, firstRangeEndOffset)) {
              highlightedHints.add(inlay);
              if (i == currentHintIndex && currentHint == null) currentHint = inlay;
            }
            int secondRangeEndOffset = nextDelimiter.getTextRange().getStartOffset();
            if (secondRangeEndOffset > firstRangeEndOffset) {
              int secondRangeStartOffset = CharArrayUtil.shiftBackward(text, secondRangeEndOffset - 1, WHITESPACE_OR_LINE_BREAKS) + 1;
              highlightedHints.addAll(presentationManager.getParameterHintsInRange(editor, secondRangeStartOffset, secondRangeEndOffset));
            }
          }
        }
      }
    }
    if (currentHint == context.getUserData(CURRENT_HINT) &&
        Objects.equals(highlightedHints, context.getUserData(HIGHLIGHTED_HINTS))) return;
    resetHints(context);
    if (currentHint != null) {
      presentationManager.setCurrent(currentHint, true);
      context.putUserData(CURRENT_HINT, currentHint);
    }
    if (!ContainerUtil.isEmpty(highlightedHints)) {
      for (Inlay highlightedHint : highlightedHints) {
        presentationManager.setHighlighted(highlightedHint, true);
      }
      context.putUserData(HIGHLIGHTED_HINTS, highlightedHints);
    }
  }

  private static void resetHints(@NotNull UserDataHolder context) {
    ParameterHintsPresentationManager presentationManager = ParameterHintsPresentationManager.getInstance();
    Inlay currentHint = context.getUserData(CURRENT_HINT);
    if (currentHint != null) {
      presentationManager.setCurrent(currentHint, false);
      context.putUserData(CURRENT_HINT, null);
    }
    List<Inlay> highlightedHints = context.getUserData(HIGHLIGHTED_HINTS);
    if (highlightedHints != null) {
      for (Inlay hint : highlightedHints) {
        presentationManager.setHighlighted(hint, false);
      }
      context.putUserData(HIGHLIGHTED_HINTS, null);
    }
  }

  @Override
  public void dispose(@NotNull DeleteParameterInfoContext context) {
    Editor editor = context.getEditor();
    if (!(editor instanceof EditorWindow) && CodeInsightSettings.getInstance().SHOW_PARAMETER_NAME_HINTS_ON_COMPLETION) {
      resetHints(context.getCustomContext());
      PsiElement parameterOwner = context.getParameterOwner();
      if (!editor.isDisposed() && parameterOwner != null && parameterOwner.isValid()) {
        ParameterHintsPass.syncUpdate(parameterOwner.getParent(), editor);
      }
    }
  }

  private static PsiSubstitutor getCandidateInfoSubstitutor(PsiElement argList, CandidateInfo candidate, boolean resolveResult) {
    return candidate instanceof MethodCandidateInfo &&
           ((MethodCandidateInfo)candidate).isInferencePossible()
           ? ((MethodCandidateInfo)candidate)
             .inferTypeArguments(resolveResult ? DefaultParameterTypeInferencePolicy.INSTANCE
                                               : CompletionParameterTypeInferencePolicy.INSTANCE, true)
           : candidate.getSubstitutor();
  }

  private static boolean isAssignableParametersBeforeGivenIndex(final PsiParameter[] parms,
                                                                final PsiExpression[] args,
                                                                int length,
                                                                PsiSubstitutor substitutor) {
    for (int j = 0; j < length; j++) {
      PsiParameter parm = parms[j];
      PsiExpression arg = args[j];
      assert parm.isValid();
      assert arg.isValid();
      PsiType parmType = parm.getType();
      PsiType argType = arg.getType();
      if (argType == null) continue;
      if (parmType instanceof PsiEllipsisType ) {
        parmType = ((PsiEllipsisType)parmType).getComponentType();
      }
      parmType = substitutor.substitute(parmType);

      if (!parmType.isAssignableFrom(argType) &&
          !(parm.getType() instanceof PsiEllipsisType && argType instanceof PsiArrayType &&
            parmType.isAssignableFrom(((PsiArrayType)argType).getComponentType()))) {
        return false;
      }
    }
    return true;
  }

  @Override
  @NotNull
  public Class<PsiExpressionList> getArgumentListClass() {
    return PsiExpressionList.class;
  }

  @Override
  @NotNull
  public IElementType getActualParametersRBraceType() {
    return JavaTokenType.RBRACE;
  }

  @Override
  @NotNull
  public Set<Class> getArgumentListAllowedParentClasses() {
    return ourArgumentListAllowedParentClassesSet;
  }

  @NotNull
  @Override
  public Set<Class> getArgListStopSearchClasses() {
    return ourStopSearch;
  }

  @Override
  @NotNull
  public IElementType getActualParameterDelimiterType() {
    return JavaTokenType.COMMA;
  }

  @Override
  @NotNull
  public PsiExpression[] getActualParameters(@NotNull PsiExpressionList psiExpressionList) {
    return psiExpressionList.getExpressions();
  }

  private static PsiCall getCall(PsiExpressionList list) {
    PsiElement listParent = list.getParent();
    if (listParent instanceof PsiMethodCallExpression) {
      return (PsiCall)listParent;
    }
    if (listParent instanceof PsiNewExpression) {
      return (PsiCall)listParent;
    }
    if (listParent instanceof PsiAnonymousClass) {
      return (PsiCall)listParent.getParent();
    }
    if (listParent instanceof PsiEnumConstant) {
      return (PsiCall)listParent;
    }
    return null;
  }



  private static CandidateInfo[] getMethods(PsiExpressionList argList) {
    final PsiCall call = getCall(argList);
    PsiResolveHelper helper = JavaPsiFacade.getInstance(argList.getProject()).getResolveHelper();

    if (call instanceof PsiCallExpression) {
      CandidateInfo[] candidates = getCandidates((PsiCallExpression)call);
      ArrayList<CandidateInfo> result = new ArrayList<>();

      if (!(argList.getParent() instanceof PsiAnonymousClass)) {
        cand:
        for (CandidateInfo candidate : candidates) {
          PsiMethod methodCandidate = (PsiMethod)candidate.getElement();

          for (CandidateInfo info : result) {
            if (MethodSignatureUtil.isSuperMethod(methodCandidate, (PsiMethod)info.getElement())) {
              continue cand;
            }
          }
          if (candidate.isStaticsScopeCorrect()) {
            boolean accessible = candidate.isAccessible();
            if (!accessible && methodCandidate.getModifierList().hasModifierProperty(PsiModifier.PRIVATE)) {
              // privates are accessible within one file
              accessible = JavaPsiFacade.getInstance(methodCandidate.getProject()).getResolveHelper()
                .isAccessible(methodCandidate, methodCandidate.getModifierList(), call, null, null);
            }
            if (accessible) result.add(candidate);
          }
        }
      }
      else {
        PsiClass aClass = (PsiClass)argList.getParent();
        for (CandidateInfo candidate : candidates) {
          if (candidate.isStaticsScopeCorrect() && helper.isAccessible((PsiMethod)candidate.getElement(), argList, aClass)) {
            result.add(candidate);
          }
        }
      }
      return result.isEmpty() ? candidates : result.toArray(CandidateInfo.EMPTY_ARRAY);
    }
    else {
      assert call instanceof PsiEnumConstant;
      //We are inside our own enum, no isAccessible check needed
      PsiMethod[] constructors = ((PsiEnumConstant)call).getContainingClass().getConstructors();
      CandidateInfo[] result = new CandidateInfo[constructors.length];

      for (int i = 0; i < constructors.length; i++) {
        result[i] = new CandidateInfo(constructors[i], PsiSubstitutor.EMPTY);
      }
      return result;
    }
  }

  private static CandidateInfo[] getCandidates(PsiCallExpression call) {
    final MethodCandidatesProcessor processor = new MethodResolverProcessor(call, call.getContainingFile(), new PsiConflictResolver[0]) {
      @Override
      protected boolean acceptVarargs() {
        return false;
      }
    };

    try {
      PsiScopesUtil.setupAndRunProcessor(processor, call, true);
    }
    catch (MethodProcessorSetupFailedException e) {
      return CandidateInfo.EMPTY_ARRAY;
    }
    final List<CandidateInfo> results = processor.getResults();
    return results.toArray(CandidateInfo.EMPTY_ARRAY);
  }

  public static String updateMethodPresentation(@NotNull PsiMethod method, @Nullable PsiSubstitutor substitutor, @NotNull ParameterInfoUIContext context) {
    CodeInsightSettings settings = CodeInsightSettings.getInstance();

    if (!method.isValid() || substitutor != null && !substitutor.isValid()) {
      context.setUIComponentEnabled(false);
      return null;
    }

    PsiParameter[] parms = method.getParameterList().getParameters();
    int numParams = parms.length;
    StringBuilder buffer = new StringBuilder(numParams * 8); // crude heuristics

    if (settings.SHOW_FULL_SIGNATURES_IN_PARAMETER_INFO && !context.isSingleParameterInfo()) {
      if (!method.isConstructor()) {
        PsiType returnType = method.getReturnType();
        if (substitutor != null) {
          returnType = substitutor.substitute(returnType);
        }
        assert returnType != null : method;

        appendModifierList(buffer, method);
        buffer.append(returnType.getPresentableText(true));
        buffer.append(" ");
      }
      buffer.append(method.getName());
      buffer.append("(");
    }

    int currentParameter = context.getCurrentParameterIndex();

    int highlightStartOffset = -1;
    int highlightEndOffset = -1;
    if (numParams > 0) {
      if (context.isSingleParameterInfo() && method.isVarArgs() && currentParameter >= numParams) currentParameter = numParams - 1;

      for (int j = 0; j < numParams; j++) {
        if (context.isSingleParameterInfo() && j != currentParameter) continue;

        PsiParameter param = parms[j];

        int startOffset = buffer.length();

        if (param.isValid()) {
          PsiType paramType = param.getType();
          assert paramType.isValid();
          if (substitutor != null) {
            assert substitutor.isValid();
            paramType = substitutor.substitute(paramType);
          }
          if (context.isSingleParameterInfo()) buffer.append("<b>");
          appendModifierList(buffer, param);
          String type = paramType.getPresentableText(true);
          buffer.append(context.isSingleParameterInfo() ? StringUtil.escapeXmlEntities(type) : type);
          String name = param.getName();
          if (!context.isSingleParameterInfo()) {
            buffer.append(" ");
            buffer.append(name);
          }
          if (context.isSingleParameterInfo()) buffer.append("</b>");
        }

        if (context.isSingleParameterInfo()) {
          String javaDoc = new JavaDocInfoGenerator(param.getProject(), param).generateMethodParameterJavaDoc();
          if (javaDoc != null) {
            javaDoc = removeHyperlinks(javaDoc);
            if (javaDoc.length() < 100) {
              buffer.append("&nbsp;&nbsp;<i>").append(javaDoc).append("</i>");
            }
            else {
              buffer.insert(0, "<table><tr><td valign='top'>")
                .append("</td><td style='width:400px'>&nbsp;&nbsp;<i>").append(javaDoc).append("</i></td></tr></table>");
            }
          }
        }
        else {
          int endOffset = buffer.length();

          if (j < numParams - 1) {
            buffer.append(", ");
          }

          if (context.isUIComponentEnabled() &&
              (j == currentParameter || j == numParams - 1 && param.isVarArgs() && currentParameter >= numParams)) {
            highlightStartOffset = startOffset;
            highlightEndOffset = endOffset;
          }
        }
      }
    }
    else {
      buffer.append(CodeInsightBundle.message("parameter.info.no.parameters"));
    }

    if (settings.SHOW_FULL_SIGNATURES_IN_PARAMETER_INFO && !context.isSingleParameterInfo()) {
      buffer.append(")");
    }

    String text = buffer.toString();
    if (context.isSingleParameterInfo()) {
      context.setupRawUIComponentPresentation(text);
      return text;
    }
    else {
      return context.setupUIComponentPresentation(
        text,
        highlightStartOffset,
        highlightEndOffset,
        !context.isUIComponentEnabled(),
        method.isDeprecated() && !context.isSingleParameterInfo() && !context.isSingleOverload(),
        false,
        context.getDefaultParameterColor()
      );
    }
  }

  private static String removeHyperlinks(String html) {
    return html.replaceAll("<a.*?>", "").replaceAll("</a>", "");
  }

  private static void appendModifierList(@NotNull StringBuilder buffer, @NotNull PsiModifierListOwner owner) {
    int lastSize = buffer.length();
    Set<String> shownAnnotations = new HashSet<>();
    for (PsiAnnotation annotation : AnnotationUtil.getAllAnnotations(owner, false, null, !DumbService.isDumb(owner.getProject()))) {
      final PsiJavaCodeReferenceElement element = annotation.getNameReferenceElement();
      if (element != null) {
        final PsiElement resolved = element.resolve();
        if (resolved instanceof PsiClass &&
            (!JavaDocInfoGenerator.isDocumentedAnnotationType((PsiClass)resolved) ||
             AnnotationTargetUtil.findAnnotationTarget((PsiClass)resolved, PsiAnnotation.TargetType.TYPE_USE) != null)) {
          continue;
        }

        String referenceName = element.getReferenceName();
        if (shownAnnotations.add(referenceName) || JavaDocInfoGenerator.isRepeatableAnnotationType(resolved)) {
          if (lastSize != buffer.length()) buffer.append(' ');
          buffer.append('@').append(referenceName);
        }
      }
    }
    if (lastSize != buffer.length()) buffer.append(' ');
  }

  @Override
  public void updateUI(final Object p, @NotNull final ParameterInfoUIContext context) {
    if (p instanceof CandidateInfo) {
      CandidateInfo info = (CandidateInfo)p;
      PsiMethod method = (PsiMethod)info.getElement();
      if (!method.isValid() || info instanceof MethodCandidateInfo && !((MethodCandidateInfo)info).getSiteSubstitutor().isValid()) {
        context.setUIComponentEnabled(false);
        return;
      }
      PsiElement parameterOwner = context.getParameterOwner();
      PsiCall call = parameterOwner instanceof PsiExpressionList ? getCall((PsiExpressionList)parameterOwner) : null;

      updateMethodPresentation(method, getCandidateInfoSubstitutor(parameterOwner, info, call != null && call.resolveMethod() == method), context);
    }
    else {
      updateMethodPresentation((PsiMethod)p, null, context);
    }
  }

  @Override
  public boolean supportsOverloadSwitching() {
    return CodeInsightSettings.getInstance().SHOW_PARAMETER_NAME_HINTS_ON_COMPLETION;
  }

  @Override
  public void syncUpdateOnCaretMove(@NotNull UpdateParameterInfoContext context) {
    if (!Registry.is("editor.completion.hints.virtual.comma")) return;

    Project project = context.getProject();
    Editor editor = context.getEditor();
    int inlaysBeforeCaretWithComma = getInlaysBeforeCaretWithComma(editor);
    if (inlaysBeforeCaretWithComma == 0) return;

    EditorUtil.performBeforeCommandEnd(() -> {
      if (project.isDisposed() || editor.isDisposed()) return;
      // repeat the check, just in case
      int countAgain = getInlaysBeforeCaretWithComma(editor);
      if (countAgain == 0) return;

      Caret caret = editor.getCaretModel().getCurrentCaret();
      int caretOffset = caret.getOffset();
      String textToInsert = StringUtil.repeat(", ", inlaysBeforeCaretWithComma);
      WriteAction.run(() -> editor.getDocument().insertString(caretOffset, textToInsert));
      caret.moveToOffset(caretOffset + textToInsert.length());

      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
      PsiElement exprList = context.getParameterOwner();
      if (!(exprList instanceof PsiExpressionList) || !exprList.isValid()) return;
      PsiElement call = exprList.getParent();
      if (call == null || !call.isValid()) return;
      ParameterHintsPass.syncUpdate(call, editor);
      int index = ParameterInfoUtils.getCurrentParameterIndex(exprList.getNode(), editor.getCaretModel().getOffset(), JavaTokenType.COMMA);
      highlightHints(editor, (PsiExpressionList)exprList, index, context.getCustomContext());
    });
  }

  private static int getInlaysBeforeCaretWithComma(@NotNull Editor editor) {
    Caret caret = editor.getCaretModel().getCurrentCaret();
    int caretOffset = caret.getOffset();
    ParameterHintsPresentationManager pm = ParameterHintsPresentationManager.getInstance();
    List<Inlay> inlays = pm.getParameterHintsInRange(editor, caretOffset, caretOffset);
    if (inlays.isEmpty()) return 0;

    VisualPosition caretPosition = caret.getVisualPosition();
    return ContainerUtil.count(inlays, inlay -> StringUtil.startsWithChar(pm.getHintText(inlay), ',') &&
                                                caretPosition.after(inlay.getVisualPosition()));
  }
}
