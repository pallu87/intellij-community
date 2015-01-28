/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.openapi.util.diff.impl;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.diff.DiffManagerEx;
import com.intellij.openapi.util.diff.actions.impl.*;
import com.intellij.openapi.util.diff.api.DiffTool;
import com.intellij.openapi.util.diff.api.FrameDiffTool;
import com.intellij.openapi.util.diff.api.FrameDiffTool.DiffContext;
import com.intellij.openapi.util.diff.api.FrameDiffTool.DiffViewer;
import com.intellij.openapi.util.diff.requests.DiffRequest;
import com.intellij.openapi.util.diff.requests.ErrorDiffRequest;
import com.intellij.openapi.util.diff.requests.MessageDiffRequest;
import com.intellij.openapi.util.diff.requests.NoDiffRequest;
import com.intellij.openapi.util.diff.tools.ErrorDiffTool;
import com.intellij.openapi.util.diff.tools.external.ExternalDiffTool;
import com.intellij.openapi.util.diff.tools.util.DiffDataKeys;
import com.intellij.openapi.util.diff.tools.util.PrevNextDifferenceIterable;
import com.intellij.openapi.util.diff.util.CalledInAwt;
import com.intellij.openapi.util.diff.util.DiffUserDataKeys;
import com.intellij.openapi.util.diff.util.DiffUserDataKeys.ScrollToPolicy;
import com.intellij.openapi.util.diff.util.DiffUserDataKeysEx;
import com.intellij.openapi.util.diff.util.DiffUtil;
import com.intellij.openapi.wm.ex.IdeFocusTraversalPolicy;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

@SuppressWarnings("InnerClassMayBeStatic")
public abstract class DiffRequestProcessor implements Disposable {
  private static final Logger LOG = Logger.getInstance(DiffRequestProcessor.class);

  private boolean myDisposed;

  @Nullable private final Project myProject;
  @NotNull private final DiffContext myContext;

  @NotNull private final List<DiffTool> myAvailableTools;
  @NotNull private final LinkedList<DiffTool> myToolOrder;

  @NotNull private final MyDiffWindow myDiffWindow;
  @NotNull private final OpenInEditorAction myOpenInEditorAction;
  @Nullable private DefaultActionGroup myPopupActionGroup;

  @NotNull private final MyPanel myPanel;
  @NotNull private final ModifiablePanel myContentPanel;
  @NotNull private final ModifiablePanel myToolbarPanel; // TODO: allow to call 'updateToolbar' from Viewer ?
  @NotNull private final ModifiablePanel myToolbarStatusPanel;

  @NotNull private DiffRequest myActiveRequest;

  @NotNull private ViewerState myState;

  public DiffRequestProcessor(@Nullable Project project) {
    this(project, new UserDataHolderBase());
  }

  public DiffRequestProcessor(@Nullable Project project, @NotNull UserDataHolder context) {
    myProject = project;

    myAvailableTools = DiffManagerEx.getInstance().getDiffTools();
    myToolOrder = new LinkedList<DiffTool>(myAvailableTools);

    myContext = new MyDiffContext(context);
    myActiveRequest = new NoDiffRequest();

    // UI

    myDiffWindow = new MyDiffWindow();

    myPanel = new MyPanel();
    myContentPanel = new ModifiablePanel();
    myToolbarPanel = new ModifiablePanel();
    myToolbarStatusPanel = new ModifiablePanel();

    JPanel topPanel = new JPanel(new BorderLayout());
    topPanel.add(myToolbarPanel, BorderLayout.CENTER);
    topPanel.add(myToolbarStatusPanel, BorderLayout.EAST);


    myPanel.add(topPanel, BorderLayout.NORTH);
    myPanel.add(myContentPanel, BorderLayout.CENTER);

    myPanel.setFocusTraversalPolicyProvider(true);
    myPanel.setFocusTraversalPolicy(new MyFocusTraversalPolicy());

    JComponent bottomPanel = myContext.getUserData(DiffUserDataKeysEx.BOTTOM_PANEL);
    if (bottomPanel != null) myPanel.add(bottomPanel, BorderLayout.SOUTH);
    if (bottomPanel instanceof Disposable) Disposer.register(this, (Disposable)bottomPanel);


    myOpenInEditorAction = new OpenInEditorAction(new Runnable() {
      @Override
      public void run() {
        onAfterNavigate();
      }
    });
  }

  public void init() {
    myActiveRequest.onAssigned(true);
    myState = new ErrorState((MessageDiffRequest)myActiveRequest);
    myState.init();
  }

  //
  // Update
  //

  @CalledInAwt
  public void updateRequest() {
    updateRequest(false);
  }

  @CalledInAwt
  public void updateRequest(boolean force) {
    updateRequest(force, null);
  }

  @CalledInAwt
  public abstract void updateRequest(boolean force, @Nullable ScrollToPolicy scrollToChangePolicy);

  @NotNull
  private FrameDiffTool getFittedTool() {
    List<FrameDiffTool> tools = new ArrayList<FrameDiffTool>();
    for (DiffTool tool : myToolOrder) {
      if (tool instanceof FrameDiffTool && tool.canShow(myContext, myActiveRequest)) {
        tools.add((FrameDiffTool)tool);
      }
    }

    tools = DiffUtil.filterSuppressedTools(tools);

    return tools.isEmpty() ? ErrorDiffTool.INSTANCE : tools.get(0);
  }

  @NotNull
  private List<FrameDiffTool> getAvailableFittedTools() {
    List<FrameDiffTool> tools = new ArrayList<FrameDiffTool>();
    for (DiffTool tool : myAvailableTools) {
      if (tool instanceof FrameDiffTool && tool.canShow(myContext, myActiveRequest)) {
        tools.add((FrameDiffTool)tool);
      }
    }

    return DiffUtil.filterSuppressedTools(tools);
  }

  private void moveToolOnTop(@NotNull DiffTool tool) {
    myToolOrder.remove(tool);

    FrameDiffTool toolToReplace = getFittedTool();

    int index;
    for (index = 0; index < myToolOrder.size(); index++) {
      if (myToolOrder.get(index) == toolToReplace) break;
    }
    myToolOrder.add(index, tool);
  }

  @NotNull
  private ViewerState createState() {
    FrameDiffTool frameTool = getFittedTool();

    DiffViewer viewer = frameTool.createComponent(myContext, myActiveRequest);

    DiffViewerWrapper wrapper = myActiveRequest.getUserData(DiffViewerWrapper.KEY);
    if (wrapper == null) {
      return new DefaultState(viewer, frameTool);
    }
    else {
      return new WrapperState(viewer, frameTool, wrapper);
    }
  }

  //
  // Abstract
  //

  @CalledInAwt
  protected void applyRequest(@NotNull DiffRequest request, boolean force, @Nullable ScrollToPolicy scrollToChangePolicy) {
    myIterationState = IterationState.NONE;

    boolean hadFocus = isFocused();
    if (!force && request == myActiveRequest) return;

    request.putUserData(DiffUserDataKeys.SCROLL_TO_CHANGE, scrollToChangePolicy);

    myState.destroy();
    myToolbarStatusPanel.setContent(null);
    myToolbarPanel.setContent(null);
    myContentPanel.setContent(null);
    myPanel.putClientProperty(AnAction.ourClientProperty, null);

    myActiveRequest.onAssigned(false);
    myActiveRequest = request;
    myActiveRequest.onAssigned(true);

    try {
      myState = createState();
      myState.init();
    }
    catch (Exception e) {
      LOG.error(e);
      myState = new ErrorState(new ErrorDiffRequest("Error: can't show diff"), getFittedTool());
      myState.init();
    }

    if (hadFocus) requestFocusInternal();
  }

  protected void setWindowTitle(@NotNull String title) {
  }

  protected void onAfterNavigate() {
  }

  protected void onDispose() {
  }

  @Nullable
  public <T> T getContextUserData(@NotNull Key<T> key) {
    return myContext.getUserData(key);
  }

  public <T> void putContextUserData(@NotNull Key<T> key, @Nullable T value) {
    myContext.putUserData(key, value);
  }

  @NotNull
  protected List<AnAction> getNavigationActions() {
    return ContainerUtil.<AnAction>list(
      new MyPrevDifferenceAction(),
      new MyNextDifferenceAction(),
      new MyPrevChangeAction(),
      new MyNextChangeAction()
    );
  }

  //
  // Misc
  //

  public boolean isWindowFocused() {
    Window window = SwingUtilities.getWindowAncestor(myPanel);
    return window != null && window.isFocused();
  }

  public boolean isFocused() {
    return DiffUtil.isFocusedComponent(myProject, myPanel);
  }

  public void requestFocus() {
    DiffUtil.requestFocus(myProject, getPreferredFocusedComponent());
  }

  protected void requestFocusInternal() {
    JComponent component = getPreferredFocusedComponent();
    if (component != null) component.requestFocus();
  }

  @Override
  public void dispose() {
    if (myDisposed) return;
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        if (myDisposed) return;
        myDisposed = true;

        onDispose();

        myState.destroy();
        myToolbarStatusPanel.setContent(null);
        myToolbarPanel.setContent(null);
        myContentPanel.setContent(null);

        myActiveRequest.onAssigned(false);
      }
    });
  }

  @NotNull
  protected DefaultActionGroup collectToolbarActions(@Nullable List<AnAction> viewerActions) {
    DefaultActionGroup group = new DefaultActionGroup();

    List<AnAction> navigationActions = new ArrayList<AnAction>();
    navigationActions.addAll(getNavigationActions());
    navigationActions.add(myOpenInEditorAction);
    navigationActions.add(new MyChangeDiffToolAction());
    DiffUtil.addActionBlock(group,
                            navigationActions);

    DiffUtil.addActionBlock(group, viewerActions);

    List<AnAction> requestContextActions = myActiveRequest.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS);
    DiffUtil.addActionBlock(group, requestContextActions);

    List<AnAction> contextActions = myContext.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS);
    DiffUtil.addActionBlock(group, contextActions);

    DiffUtil.addActionBlock(group,
                            new ShowInExternalToolAction(),
                            new ShowOldDiffAction(),
                            ActionManager.getInstance().getAction(IdeActions.ACTION_CONTEXT_HELP));

    return group;
  }

  @NotNull
  protected DefaultActionGroup collectPopupActions(@Nullable List<AnAction> viewerActions) {
    DefaultActionGroup group = new DefaultActionGroup();

    List<AnAction> selectToolActions = new ArrayList<AnAction>();
    for (DiffTool tool : getAvailableFittedTools()) {
      if (tool == myState.getActiveTool()) continue;
      selectToolActions.add(new DiffToolToggleAction(tool));
    }
    DiffUtil.addActionBlock(group, selectToolActions);

    DiffUtil.addActionBlock(group, viewerActions);

    return group;
  }

  protected void buildToolbar(@Nullable List<AnAction> viewerActions) {
    ActionGroup group = collectToolbarActions(viewerActions);

    myToolbarPanel.setContent(DiffUtil.createToolbar(group).getComponent());
    for (AnAction action : group.getChildren(null)) {
      action.registerCustomShortcutSet(action.getShortcutSet(), myPanel);
    }
  }

  protected void buildActionPopup(@Nullable List<AnAction> viewerActions) {
    ShowActionGroupPopupAction action = new ShowActionGroupPopupAction();
    action.registerCustomShortcutSet(action.getShortcutSet(), myPanel);

    myPopupActionGroup = collectPopupActions(viewerActions);
  }

  private void setTitle(@Nullable String title) {
    if (title == null) title = "Diff";
    setWindowTitle(title);
  }

  //
  // Getters
  //

  @NotNull
  public JComponent getComponent() {
    return myPanel;
  }

  @Nullable
  public JComponent getPreferredFocusedComponent() {
    JComponent component = myState.getPreferredFocusedComponent();
    return component != null ? component : myToolbarPanel.getContent();
  }

  @Nullable
  public Project getProject() {
    return myProject;
  }

  @NotNull
  public DiffContext getContext() {
    return myContext;
  }

  //
  // Actions
  //

  private class ShowInExternalToolAction extends DumbAwareAction {
    public ShowInExternalToolAction() {
      super("Show in external tool", null, AllIcons.General.ExternalToolsSmall);
    }

    @Override
    public void update(AnActionEvent e) {
      if (!ExternalDiffTool.isEnabled()) {
        e.getPresentation().setEnabledAndVisible(false);
        return;
      }
      e.getPresentation().setEnabled(ExternalDiffTool.canShow(myActiveRequest));
      e.getPresentation().setVisible(true);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      try {
        ExternalDiffTool.showRequest(e.getProject(), myActiveRequest);
      }
      catch (Throwable ex) {
        Messages.showErrorDialog(e.getProject(), ex.getMessage(), "Can't Show Diff In External Tool");
      }
    }
  }

  private class MyChangeDiffToolAction extends ComboBoxAction implements DumbAware {
    public MyChangeDiffToolAction() {
      // TODO: add icons for diff tools, show only icon in toolbar - to reduce jumping on change ?
      setEnabledInModalContext(true);
    }

    @Override
    public void update(AnActionEvent e) {
      Presentation presentation = e.getPresentation();

      DiffTool activeTool = myState.getActiveTool();
      presentation.setText(activeTool.getName());

      if (activeTool == ErrorDiffTool.INSTANCE) {
        presentation.setEnabledAndVisible(false);
      }

      for (DiffTool tool : getAvailableFittedTools()) {
        if (tool != activeTool) {
          presentation.setEnabledAndVisible(true);
          return;
        }
      }

      presentation.setEnabledAndVisible(false);
    }

    @NotNull
    @Override
    protected DefaultActionGroup createPopupActionGroup(JComponent button) {
      DefaultActionGroup group = new DefaultActionGroup();
      for (DiffTool tool : getAvailableFittedTools()) {
        group.add(new DiffToolToggleAction(tool));
      }

      return group;
    }
  }

  private class DiffToolToggleAction extends AnAction implements DumbAware {
    @NotNull private final DiffTool myDiffTool;

    private DiffToolToggleAction(@NotNull DiffTool tool) {
      super(tool.getName());
      setEnabledInModalContext(true);
      myDiffTool = tool;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      if (myState.getActiveTool() == myDiffTool) return;

      moveToolOnTop(myDiffTool);

      updateRequest(true);
    }
  }

  private class ShowActionGroupPopupAction extends DumbAwareAction {

    public ShowActionGroupPopupAction() {
      setShortcutSet(CommonShortcuts.getDiff()); // TODO: configurable shortcut
    }

    @Override
    public void update(AnActionEvent e) {
      e.getPresentation().setEnabled(myPopupActionGroup != null && myPopupActionGroup.getChildrenCount() > 0);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      assert myPopupActionGroup != null;
      ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup("Diff Actions", myPopupActionGroup, e.getDataContext(),
                                                                            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);
      popup.showInCenterOf(myPanel);
    }
  }

  //
  // Navigation
  //

  private enum IterationState {NEXT, PREV, NONE}

  @NotNull private IterationState myIterationState = IterationState.NONE;

  @CalledInAwt
  protected boolean hasNextChange() {
    return false;
  }

  @CalledInAwt
  protected boolean hasPrevChange() {
    return false;
  }

  @CalledInAwt
  protected void goToNextChange(boolean fromDifferences) {
  }

  @CalledInAwt
  protected void goToPrevChange(boolean fromDifferences) {
  }

  @CalledInAwt
  protected boolean isNavigationEnabled() {
    return false;
  }

  protected class MyNextDifferenceAction extends NextDifferenceAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      PrevNextDifferenceIterable iterable = DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE.getData(e.getDataContext());
      if (iterable != null && iterable.canGoNext()) {
        e.getPresentation().setEnabled(true);
        return;
      }

      if (isNavigationEnabled() && hasNextChange()) {
        e.getPresentation().setEnabled(true);
        return;
      }

      e.getPresentation().setEnabled(false);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      PrevNextDifferenceIterable iterable = DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE.getData(e.getDataContext());
      if (iterable != null && iterable.canGoNext()) {
        iterable.goNext();
        myIterationState = IterationState.NONE;
        return;
      }

      if (myIterationState != IterationState.NEXT) {
        // TODO: provide "change" word in chain UserData - for tests/etc
        if (iterable != null) iterable.notify("Press again to go to the next file");
        myIterationState = IterationState.NEXT;
        return;
      }

      goToNextChange(true);
    }
  }

  protected class MyPrevDifferenceAction extends PrevDifferenceAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      PrevNextDifferenceIterable iterable = DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE.getData(e.getDataContext());
      if (iterable != null && iterable.canGoPrev()) {
        e.getPresentation().setEnabled(true);
        return;
      }

      if (isNavigationEnabled() && hasPrevChange()) {
        e.getPresentation().setEnabled(true);
        return;
      }

      e.getPresentation().setEnabled(false);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      PrevNextDifferenceIterable iterable = DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE.getData(e.getDataContext());
      if (iterable != null && iterable.canGoPrev()) {
        iterable.goPrev();
        myIterationState = IterationState.NONE;
        return;
      }

      if (myIterationState != IterationState.PREV) {
        if (iterable != null) iterable.notify("Press again to go to the previous file");
        myIterationState = IterationState.PREV;
        return;
      }

      goToPrevChange(true);
    }
  }

  // Iterate presentable

  protected class MyNextChangeAction extends NextChangeAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      if (!isNavigationEnabled()) {
        e.getPresentation().setEnabledAndVisible(false);
        return;
      }

      e.getPresentation().setVisible(true);
      e.getPresentation().setEnabled(hasNextChange());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      goToNextChange(false);
    }
  }

  protected class MyPrevChangeAction extends PrevChangeAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      if (!isNavigationEnabled()) {
        e.getPresentation().setEnabledAndVisible(false);
        return;
      }

      e.getPresentation().setVisible(true);
      e.getPresentation().setEnabled(hasPrevChange());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      goToPrevChange(false);
    }
  }

  //
  // Helpers
  //

  private class MyPanel extends JPanel implements DataProvider {
    public MyPanel() {
      super(new BorderLayout());
    }

    @Nullable
    @Override
    public Object getData(@NonNls String dataId) {
      if (OpenInEditorAction.KEY.is(dataId)) {
        return myOpenInEditorAction;
      }
      else if (DiffDataKeys.DIFF_REQUEST.is(dataId)) {
        return myActiveRequest;
      }
      else if (CommonDataKeys.PROJECT.is(dataId)) {
        return myProject;
      }
      else if (PlatformDataKeys.HELP_ID.is(dataId)) {
        if (myActiveRequest.getUserData(DiffUserDataKeys.HELP_ID) != null) {
          return myActiveRequest.getUserData(DiffUserDataKeys.HELP_ID);
        }
        else {
          return "reference.dialogs.diff.file";
        }
      }
      else if (DiffDataKeys.DIFF_CONTEXT.is(dataId)) {
        return myContext;
      }

      Object data = myState.getData(dataId);
      if (data != null) return data;

      DataProvider requestProvider = myActiveRequest.getUserData(DiffUserDataKeys.DATA_PROVIDER);
      if (requestProvider != null) {
        data = requestProvider.getData(dataId);
        if (data != null) return data;
      }

      DataProvider contextProvider = myContext.getUserData(DiffUserDataKeys.DATA_PROVIDER);
      if (contextProvider != null) {
        data = contextProvider.getData(dataId);
        if (data != null) return data;
      }
      return null;
    }
  }

  private class MyFocusTraversalPolicy extends IdeFocusTraversalPolicy {
    @Override
    public final Component getDefaultComponentImpl(final Container focusCycleRoot) {
      JComponent component = DiffRequestProcessor.this.getPreferredFocusedComponent();
      if (component == null) return null;
      return IdeFocusTraversalPolicy.getPreferredFocusedComponent(component, this);
    }
  }

  private class MyDiffWindow implements DiffContext.DiffWindow {
    @Override
    public boolean isFocused() {
      return DiffRequestProcessor.this.isFocused();
    }

    @Override
    public boolean isWindowFocused() {
      return DiffRequestProcessor.this.isWindowFocused();
    }

    @Override
    public void requestFocus() {
      DiffRequestProcessor.this.requestFocusInternal();
    }
  }

  private class MyDiffContext implements DiffContext {
    @NotNull private final UserDataHolder myContext;

    public MyDiffContext(@NotNull UserDataHolder context) {
      myContext = context;
    }

    @Nullable
    @Override
    public Project getProject() {
      return DiffRequestProcessor.this.getProject();
    }

    @NotNull
    @Override
    public DiffWindow getDiffWindow() {
      return myDiffWindow;
    }

    @Nullable
    @Override
    public <T> T getUserData(@NotNull Key<T> key) {
      return myContext.getUserData(key);
    }

    @Override
    public <T> void putUserData(@NotNull Key<T> key, @Nullable T value) {
      myContext.putUserData(key, value);
    }
  }

  //
  // States
  //

  private interface ViewerState {
    void init();

    void destroy();

    @Nullable
    JComponent getPreferredFocusedComponent();

    @Nullable
    Object getData(@NonNls String dataId);

    @NotNull
    DiffTool getActiveTool();
  }

  private class ErrorState implements ViewerState {
    @Nullable private final DiffTool myDiffTool;
    @NotNull private final MessageDiffRequest myRequest;

    @NotNull private final DiffViewer myViewer;

    public ErrorState(@NotNull MessageDiffRequest request) {
      this(request, null);
    }

    public ErrorState(@NotNull MessageDiffRequest request, @Nullable DiffTool diffTool) {
      myDiffTool = diffTool;
      myRequest = request;

      myViewer = ErrorDiffTool.INSTANCE.createComponent(myContext, myRequest);
    }

    @Override
    public void init() {
      myContentPanel.setContent(myViewer.getComponent());

      FrameDiffTool.ToolbarComponents init = myViewer.init();
      buildToolbar(init.toolbarActions);

      myPanel.validate();
    }

    @Override
    public void destroy() {
      Disposer.dispose(myViewer);
    }

    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
      return null;
    }

    @Nullable
    @Override
    public Object getData(@NonNls String dataId) {
      return null;
    }

    @NotNull
    @Override
    public DiffTool getActiveTool() {
      return myDiffTool != null ? myDiffTool : ErrorDiffTool.INSTANCE;
    }
  }

  private class DefaultState implements ViewerState {
    @NotNull private final DiffViewer myViewer;
    @NotNull private final FrameDiffTool myTool;

    public DefaultState(@NotNull DiffViewer viewer, @NotNull FrameDiffTool tool) {
      myViewer = viewer;
      myTool = tool;
    }

    @Override
    public void init() {
      myContentPanel.setContent(myViewer.getComponent());
      setTitle(myActiveRequest.getTitle());

      myPanel.validate();

      FrameDiffTool.ToolbarComponents toolbarComponents = myViewer.init();

      buildToolbar(toolbarComponents.toolbarActions);
      buildActionPopup(toolbarComponents.popupActions);

      myToolbarStatusPanel.setContent(toolbarComponents.statusPanel);

      myPanel.validate();
    }

    @Override
    public void destroy() {
      Disposer.dispose(myViewer);
    }

    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
      return myViewer.getPreferredFocusedComponent();
    }

    @NotNull
    @Override
    public DiffTool getActiveTool() {
      return myTool;
    }

    @Nullable
    @Override
    public Object getData(@NonNls String dataId) {
      if (DiffDataKeys.DIFF_VIEWER.is(dataId)) {
        return myViewer;
      }
      return null;
    }
  }

  private class WrapperState implements ViewerState {
    @NotNull private final DiffViewer myViewer;
    @NotNull private final FrameDiffTool myTool;

    @NotNull private DiffViewer myWrapperViewer;

    public WrapperState(@NotNull DiffViewer viewer, @NotNull FrameDiffTool tool, @NotNull DiffViewerWrapper wrapper) {
      myViewer = viewer;
      myTool = tool;
      myWrapperViewer = wrapper.createComponent(myContext, myActiveRequest, myViewer);
    }

    @Override
    public void init() {
      myContentPanel.setContent(myWrapperViewer.getComponent());
      setTitle(myActiveRequest.getTitle());

      myPanel.validate();


      FrameDiffTool.ToolbarComponents toolbarComponents1 = myViewer.init();
      FrameDiffTool.ToolbarComponents toolbarComponents2 = myWrapperViewer.init();

      List<AnAction> toolbarActions = new ArrayList<AnAction>();
      if (toolbarComponents1.toolbarActions != null) toolbarActions.addAll(toolbarComponents1.toolbarActions);
      if (toolbarComponents2.toolbarActions != null) {
        if (!toolbarActions.isEmpty() && !toolbarComponents2.toolbarActions.isEmpty()) toolbarActions.add(Separator.getInstance());
        toolbarActions.addAll(toolbarComponents2.toolbarActions);
      }
      buildToolbar(toolbarActions);

      List<AnAction> popupActions = new ArrayList<AnAction>();
      if (toolbarComponents1.popupActions != null) popupActions.addAll(toolbarComponents1.popupActions);
      if (toolbarComponents2.popupActions != null) {
        if (!popupActions.isEmpty() && !toolbarComponents2.popupActions.isEmpty()) popupActions.add(Separator.getInstance());
        popupActions.addAll(toolbarComponents2.popupActions);
      }
      buildActionPopup(popupActions);


      myToolbarStatusPanel.setContent(toolbarComponents1.statusPanel); // TODO: combine both panels ?

      myPanel.validate();
    }

    @Override
    public void destroy() {
      Disposer.dispose(myViewer);
      Disposer.dispose(myWrapperViewer);
    }

    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
      return myWrapperViewer.getPreferredFocusedComponent();
    }

    @NotNull
    @Override
    public DiffTool getActiveTool() {
      return myTool;
    }

    @Nullable
    @Override
    public Object getData(@NonNls String dataId) {
      if (DiffDataKeys.DIFF_VIEWER.is(dataId)) {
        return myWrapperViewer;
      }
      return null;
    }
  }
}
