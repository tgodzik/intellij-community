// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes.committed;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.versionBrowser.ChangeBrowserSettings;
import com.intellij.openapi.vcs.versionBrowser.ChangesBrowserSettingsEditor;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vcs.versionBrowser.DateFilterComponent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.AsynchConsumer;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.*;

/**
 * @author yole
 */
public class CompositeCommittedChangesProvider implements CommittedChangesProvider<CommittedChangeList, CompositeCommittedChangesProvider.CompositeChangeBrowserSettings> {
  private final Project myProject;
  private final List<AbstractVcs> myBaseVcss;

  public CompositeCommittedChangesProvider(final Project project, final AbstractVcs... baseVcss) {
    myProject = project;
    myBaseVcss = new ArrayList<>();
    Collections.addAll(myBaseVcss, baseVcss);
  }

  @Override
  @NotNull
  public CompositeCommittedChangesProvider.CompositeChangeBrowserSettings createDefaultSettings() {
    Map<AbstractVcs, ChangeBrowserSettings> map = new HashMap<>();
    for(AbstractVcs vcs: myBaseVcss) {
      final CommittedChangesProvider provider = vcs.getCommittedChangesProvider();
      assert provider != null;
      map.put(vcs, provider.createDefaultSettings());
    }
    return new CompositeChangeBrowserSettings(map);
  }

  @Override
  public ChangesBrowserSettingsEditor<CompositeCommittedChangesProvider.CompositeChangeBrowserSettings> createFilterUI(final boolean showDateFilter) {
    return new CompositeChangesBrowserSettingsEditor();
  }

  @Override
  public CompositeRepositoryLocation getLocationFor(final FilePath root) {
    final AbstractVcs vcs = ProjectLevelVcsManager.getInstance(myProject).getVcsFor(root);
    if (vcs != null) {
      final CommittedChangesProvider committedChangesProvider = vcs.getCommittedChangesProvider();
      if (committedChangesProvider != null) {
        return new CompositeRepositoryLocation(committedChangesProvider,
                                               CommittedChangesCache.getInstance(myProject).getLocationCache().getLocation(vcs, root, false));
      }
    }
    return null;
  }

  @Override
  @Nullable
  public VcsCommittedListsZipper getZipper() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<CommittedChangeList> getCommittedChanges(CompositeCommittedChangesProvider.CompositeChangeBrowserSettings settings,
                                                       RepositoryLocation location, final int maxCount) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void loadCommittedChanges(CompositeChangeBrowserSettings settings,
                                   RepositoryLocation location,
                                   int maxCount,
                                   AsynchConsumer<? super CommittedChangeList> consumer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ChangeListColumn[] getColumns() {
    Set<ChangeListColumn> columns = new LinkedHashSet<>();
    for(AbstractVcs vcs: myBaseVcss) {
      final CommittedChangesProvider provider = vcs.getCommittedChangesProvider();
      assert provider != null;
      ChangeListColumn[] providerColumns = provider.getColumns();
      for(ChangeListColumn col: providerColumns) {
        if (col == ChangeListColumn.DATE || col == ChangeListColumn.DESCRIPTION || col == ChangeListColumn.NAME ||
            col instanceof ChangeListColumn.ChangeListNumberColumn) {
          columns.add(col);
        }
      }
    }
    return columns.toArray(new ChangeListColumn[0]);
  }

  @Override
  @Nullable
  public VcsCommittedViewAuxiliary createActions(@NotNull DecoratorManager manager, @Nullable RepositoryLocation location) {
    JTabbedPane tabbedPane = null;
    List<AnAction> actions = null;
    List<AnAction> toolbarActions = null;

    final List<Runnable> calledOnDispose = new ArrayList<>();
    for (AbstractVcs baseVcs : myBaseVcss) {
      final CommittedChangesProvider provider = baseVcs.getCommittedChangesProvider();
      if (provider != null) {
        VcsCommittedViewAuxiliary auxiliary = provider.createActions(manager, location);
        if (auxiliary != null) {
          if (tabbedPane == null) {
            tabbedPane = new JBTabbedPane();
            actions = new ArrayList<>();
            toolbarActions = new ArrayList<>();
          }
          actions.addAll(auxiliary.getPopupActions());
          toolbarActions.addAll(auxiliary.getToolbarActions());
          calledOnDispose.add(auxiliary.getCalledOnViewDispose());
        }
      }
    }
    if (tabbedPane != null) {
      final JPanel panel = new JPanel();
      panel.add(tabbedPane);
      return new VcsCommittedViewAuxiliary(actions, () -> {
        for (Runnable runnable : calledOnDispose) {
          runnable.run();
        }
      }, toolbarActions);
    }
    return null;
  }

  @Override
  public int getUnlimitedCountValue() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Pair<CommittedChangeList, FilePath> getOneList(VirtualFile file, VcsRevisionNumber number) {
    throw new UnsupportedOperationException();
  }

  @Override
  public RepositoryLocation getForNonLocal(VirtualFile file) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean supportsIncomingChanges() {
    return true;
  }

  public static class CompositeChangeBrowserSettings extends ChangeBrowserSettings {
    private final Map<AbstractVcs, ChangeBrowserSettings> myMap;
    private final Set<AbstractVcs> myEnabledVcs = new HashSet<>();

    public CompositeChangeBrowserSettings(final Map<AbstractVcs, ChangeBrowserSettings> map) {
      myMap = map;
      myEnabledVcs.addAll(map.keySet());
    }

    public void put(final AbstractVcs vcs, final ChangeBrowserSettings settings) {
      myMap.put(vcs, settings);
    }

    public ChangeBrowserSettings get(final AbstractVcs vcs) {
      return myMap.get(vcs);
    }

    public void setEnabledVcss(Collection<? extends AbstractVcs> vcss) {
      myEnabledVcs.clear();
      myEnabledVcs.addAll(vcss);
    }

    public Collection<AbstractVcs> getEnabledVcss() {
      return myEnabledVcs;
    }
  }

  private class CompositeChangesBrowserSettingsEditor implements ChangesBrowserSettingsEditor<CompositeChangeBrowserSettings> {
    private final JPanel myCompositePanel;
    private final DateFilterComponent myDateFilter;
    private CompositeChangeBrowserSettings mySettings;
    private final Map<AbstractVcs, ChangesBrowserSettingsEditor> myEditors = new HashMap<>();
    private final Map<AbstractVcs, JCheckBox> myEnabledCheckboxes = new HashMap<>();

    CompositeChangesBrowserSettingsEditor() {
      myCompositePanel = new JPanel();
      myCompositePanel.setLayout(new BoxLayout(myCompositePanel, BoxLayout.Y_AXIS));
      myDateFilter = new DateFilterComponent();
      myCompositePanel.add(myDateFilter.getPanel());
      for(AbstractVcs vcs: myBaseVcss) {
        final CommittedChangesProvider provider = vcs.getCommittedChangesProvider();
        assert provider != null;
        final ChangesBrowserSettingsEditor editor = provider.createFilterUI(false);
        myEditors.put(vcs, editor);

        JPanel wrapperPane = new JPanel(new BorderLayout());
        wrapperPane.setBorder(IdeBorderFactory.createTitledBorder(vcs.getDisplayName()));
        final JCheckBox checkBox = new JCheckBox(VcsBundle.message("composite.change.provider.include.vcs.checkbox", vcs.getDisplayName()), true);
        checkBox.addActionListener(new ActionListener() {
          @Override
          public void actionPerformed(final ActionEvent e) {
            updateVcsEnabled(checkBox, editor);
          }
        });
        wrapperPane.add(checkBox, BorderLayout.NORTH);
        myEnabledCheckboxes.put(vcs, checkBox);
        wrapperPane.add(editor.getComponent(), BorderLayout.CENTER);
        myCompositePanel.add(wrapperPane);
      }
    }

    private void updateVcsEnabled(JCheckBox checkBox, ChangesBrowserSettingsEditor editor) {
      UIUtil.setEnabled(editor.getComponent(), checkBox.isSelected(), true);
      if (checkBox.isSelected()) {
        editor.updateEnabledControls();
      }
    }

    @Override
    public JComponent getComponent() {
      return myCompositePanel;
    }

    @Override
    public CompositeChangeBrowserSettings getSettings() {
      Set<AbstractVcs> enabledVcss = new HashSet<>();
      for(AbstractVcs vcs: myEditors.keySet()) {
        ChangeBrowserSettings settings = myEditors.get(vcs).getSettings();
        myDateFilter.saveValues(settings);
        mySettings.put(vcs, settings);
        if (myEnabledCheckboxes.get(vcs).isSelected()) {
          enabledVcss.add(vcs);
        }
      }
      mySettings.setEnabledVcss(enabledVcss);
      return mySettings;
    }

    @Override
    public void setSettings(CompositeChangeBrowserSettings settings) {
      mySettings = settings;
      boolean dateFilterInitialized = false;
      for(AbstractVcs vcs: myEditors.keySet()) {
        final ChangeBrowserSettings vcsSettings = mySettings.get(vcs);
        final ChangesBrowserSettingsEditor editor = myEditors.get(vcs);
        //noinspection unchecked
        editor.setSettings(vcsSettings);
        if (!dateFilterInitialized) {
          myDateFilter.initValues(vcsSettings);
          dateFilterInitialized = true;
        }
        final JCheckBox checkBox = myEnabledCheckboxes.get(vcs);
        checkBox.setSelected(settings.getEnabledVcss().contains(vcs));
        updateVcsEnabled(checkBox, editor);
      }
    }

    @Override
    @Nullable
    public String validateInput() {
      for(ChangesBrowserSettingsEditor editor: myEditors.values()) {
        String result = editor.validateInput();
        if (result != null) return result;
      }
      return null;
    }

    @Override
    public void updateEnabledControls() {
      for(ChangesBrowserSettingsEditor editor: myEditors.values()) {
        editor.updateEnabledControls();
      }
    }

    @Override
    public String getDimensionServiceKey() {
      @NonNls StringBuilder result = new StringBuilder();
      result.append("Composite");
      for(AbstractVcs vcs: myBaseVcss) {
        result.append(".").append(vcs.getDisplayName());
      }
      return result.toString();
    }
  }
}
