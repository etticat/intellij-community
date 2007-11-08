package com.intellij.ide.favoritesTreeView.actions;

import com.intellij.ide.favoritesTreeView.FavoriteNodeProvider;
import com.intellij.ide.favoritesTreeView.FavoritesManager;
import com.intellij.ide.favoritesTreeView.smartPointerPsiNodes.ClassSmartPointerNode;
import com.intellij.ide.favoritesTreeView.smartPointerPsiNodes.FieldSmartPointerNode;
import com.intellij.ide.favoritesTreeView.smartPointerPsiNodes.MethodSmartPointerNode;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ViewSettings;
import com.intellij.ide.projectView.impl.AbstractProjectViewPane;
import com.intellij.ide.projectView.impl.ModuleGroup;
import com.intellij.ide.projectView.impl.PackageViewPane;
import com.intellij.ide.projectView.impl.nodes.*;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.lang.properties.ResourceBundle;
import com.intellij.lang.properties.projectView.ResourceBundleNode;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.DataConstantsEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * User: anna
 * Date: Feb 15, 2005
 */
public class AddToFavoritesAction extends AnAction {
  private static final Logger LOG = Logger.getInstance("com.intellij.ide.favoritesTreeView.actions.AddToFavoritesAction");

  private String myFavoritesListName;

  public AddToFavoritesAction(String choosenList) {
    super(choosenList.replaceAll("_", "__"));
    myFavoritesListName = choosenList;
  }

  public void actionPerformed(AnActionEvent e) {
    final DataContext dataContext = e.getDataContext();

    Collection<AbstractTreeNode> nodesToAdd = getNodesToAdd(dataContext, true);

    if (nodesToAdd != null && !nodesToAdd.isEmpty()) {
      Project project = e.getData(PlatformDataKeys.PROJECT);
      FavoritesManager.getInstance(project).addRoots(myFavoritesListName, nodesToAdd);
    }
  }

  public static Collection<AbstractTreeNode> getNodesToAdd(final DataContext dataContext, final boolean inProjectView) {
    Project project = PlatformDataKeys.PROJECT.getData(dataContext);

    if (project == null) return null;

    Module moduleContext = DataKeys.MODULE_CONTEXT.getData(dataContext);

    Collection<AbstractTreeNode> nodesToAdd = null;
    FavoriteNodeProvider[] providers = Extensions.getExtensions(FavoriteNodeProvider.EP_NAME);
    for(FavoriteNodeProvider provider: providers) {
      nodesToAdd = provider.getFavoriteNodes(dataContext, ViewSettings.DEFAULT);
      if (nodesToAdd != null) {
        break;
      }
    }

    if (nodesToAdd == null) {
      Object elements = collectSelectedElements(dataContext);
      if (elements != null) {
        nodesToAdd = createNodes(project, moduleContext, elements, inProjectView, ViewSettings.DEFAULT);
      }
    }
    return nodesToAdd;
  }

  public void update(AnActionEvent e) {
    final DataContext dataContext = e.getDataContext();
    Project project = e.getData(PlatformDataKeys.PROJECT);
    if (project == null) {
      e.getPresentation().setEnabled(false);
    }
    else {
      e.getPresentation().setEnabled(canCreateNodes(dataContext, e));
    }
  }

  public static boolean canCreateNodes(final DataContext dataContext, final AnActionEvent e) {
    final boolean inProjectView = e.getPlace().equals(ActionPlaces.J2EE_VIEW_POPUP) ||
                                  e.getPlace().equals(ActionPlaces.STRUCTURE_VIEW_POPUP) ||
                                  e.getPlace().equals(ActionPlaces.PROJECT_VIEW_POPUP);
    return getNodesToAdd(dataContext, inProjectView) != null;
  }

  static Object retrieveData(Object object, Object data) {
    return object == null ? data : object;
  }

  private static Object collectSelectedElements(final DataContext dataContext) {
    Object elements = retrieveData(null, dataContext.getData(DataConstantsEx.RESOURCE_BUNDLE_ARRAY));
    elements = retrieveData(elements, DataKeys.PSI_ELEMENT_ARRAY.getData(dataContext));
    elements = retrieveData(elements, PlatformDataKeys.VIRTUAL_FILE_ARRAY.getData(dataContext));
    elements = retrieveData(elements, DataKeys.PSI_FILE.getData(dataContext));
    elements = retrieveData(elements, DataKeys.PSI_ELEMENT.getData(dataContext));
    elements = retrieveData(elements, PlatformDataKeys.VIRTUAL_FILE.getData(dataContext));
    elements = retrieveData(elements, dataContext.getData(DataConstantsEx.MODULE_GROUP_ARRAY));
    elements = retrieveData(elements, DataKeys.MODULE_CONTEXT_ARRAY.getData(dataContext));
    elements = retrieveData(elements, DataKeys.MODULE.getData(dataContext));
    elements = retrieveData(elements, dataContext.getData(DataConstantsEx.LIBRARY_GROUP_ARRAY));
    elements = retrieveData(elements, dataContext.getData(DataConstantsEx.NAMED_LIBRARY_ARRAY));
    return elements;
  }

  public static
  @NotNull
  Collection<AbstractTreeNode> createNodes(Project project, Module moduleContext, Object object, boolean inProjectView, ViewSettings favoritesConfig) {
    ArrayList<AbstractTreeNode> result = new ArrayList<AbstractTreeNode>();
    if (project == null) return Collections.emptyList();
    final PsiManager psiManager = PsiManager.getInstance(project);

    final String currentViewId = ProjectView.getInstance(project).getCurrentViewId();
    AbstractProjectViewPane pane = ProjectView.getInstance(project).getProjectViewPaneById(currentViewId);

    //on bundles nodes
    if (object instanceof ResourceBundle[]) {
      for (ResourceBundle bundle : (ResourceBundle[])object) {
        result.add(new ResourceBundleNode(project, bundle, favoritesConfig));
      }
      return result;
    }

    //on psi elements
    if (object instanceof PsiElement[]) {
      for (PsiElement psiElement : (PsiElement[])object) {
        addPsiElementNode(psiElement, project, result, favoritesConfig, moduleContext);
      }
      return result;
    }

    if (object instanceof PackageElement) {
      PackageElementNode node = new PackageElementNode(project, object, favoritesConfig);
      result.add(node);
      return result;
    }
    //on psi element
    if (object instanceof PsiElement) {
      Module containingModule = null;
      if (inProjectView && ProjectView.getInstance(project).isShowModules(currentViewId)) {
        if (pane != null && pane.getSelectedDescriptor() != null && pane.getSelectedDescriptor().getElement() instanceof AbstractTreeNode) {
          AbstractTreeNode abstractTreeNode = ((AbstractTreeNode)pane.getSelectedDescriptor().getElement());
          while (abstractTreeNode != null && !(abstractTreeNode.getParent() instanceof AbstractModuleNode)) {
            abstractTreeNode = abstractTreeNode.getParent();
          }
          if (abstractTreeNode != null) {
            containingModule = ((AbstractModuleNode)abstractTreeNode.getParent()).getValue();
          }
        }
      }
      addPsiElementNode((PsiElement)object, project, result, favoritesConfig, containingModule);
      return result;
    }

    if (object instanceof VirtualFile[]) {
      for (VirtualFile vFile : (VirtualFile[])object) {
        PsiElement element = psiManager.findFile(vFile);
        if (element == null) element = psiManager.findDirectory(vFile);
        addPsiElementNode(element,
                          project,
                          result,
                          favoritesConfig,
                          moduleContext);
      }
      return result;
    }

    //on form in editor
    if (object instanceof VirtualFile) {
      final VirtualFile vFile = (VirtualFile)object;
      final PsiFile psiFile = psiManager.findFile(vFile);
      addPsiElementNode(psiFile,
                        project,
                        result,
                        favoritesConfig,
                        moduleContext);
      return result;
    }

    //on module groups
    if (object instanceof ModuleGroup[]) {
      boolean isPackageView = false;
      if (currentViewId.equals(PackageViewPane.ID)) {
        isPackageView = true;
      }
      for (ModuleGroup moduleGroup : (ModuleGroup[])object) {
        if (isPackageView) {
          result.add(new PackageViewModuleGroupNode(project, moduleGroup, favoritesConfig));
        }
        else {
          result.add(new ProjectViewModuleGroupNode(project, moduleGroup, favoritesConfig));
        }
      }
      return result;
    }

    //on module nodes
    if (object instanceof Module) object = new Module[]{(Module)object};
    if (object instanceof Module[]) {
      for (Module module1 : (Module[])object) {
        if (currentViewId.equals(PackageViewPane.ID)) {
          result.add(new PackageViewModuleNode(project, module1, favoritesConfig));
        }
        else {
          result.add(new ProjectViewModuleNode(project, module1, favoritesConfig));
        }
      }
      return result;
    }

    //on library group node
    if (object instanceof LibraryGroupElement[]) {
      for (LibraryGroupElement libraryGroup : (LibraryGroupElement[])object) {
        result.add(new LibraryGroupNode(project, libraryGroup, favoritesConfig));
      }
      return result;
    }

    //on named library node
    if (object instanceof NamedLibraryElement[]) {
      for (NamedLibraryElement namedLibrary : (NamedLibraryElement[])object) {
        result.add(new NamedLibraryElementNode(project, namedLibrary, favoritesConfig));
      }
      return result;
    }
    return result;
  }

  private static void addPsiElementNode(PsiElement psiElement,
                                       final Project project,
                                       final ArrayList<AbstractTreeNode> result,
                                       final ViewSettings favoritesConfig,
                                       Module module) {
    Class<? extends AbstractTreeNode> klass = getPsiElementNodeClass(psiElement);
    if (klass == null) {
      psiElement = PsiTreeUtil.getParentOfType(psiElement, PsiFile.class);
      if (psiElement != null) {
        klass = PsiFileNode.class;
      }
    }
    final Object value = getPsiElementNodeValue(psiElement, project, module);
    try {
      if (klass != null && value != null) {
        result.add(ProjectViewNode.createTreeNode(klass, project, value, favoritesConfig));
      }
    }
    catch (Exception e) {
      LOG.error(e);
    }
  }


  private static Class<? extends AbstractTreeNode> getPsiElementNodeClass(PsiElement psiElement) {
    Class<? extends AbstractTreeNode> klass = null;
    if (psiElement instanceof PsiClass) {
      klass = ClassSmartPointerNode.class;
    }
    else if (psiElement instanceof PsiMethod) {
      klass = MethodSmartPointerNode.class;
    }
    else if (psiElement instanceof PsiField) {
      klass = FieldSmartPointerNode.class;
    }
    else if (psiElement instanceof PsiFile) {
      klass = PsiFileNode.class;
    }
    else if (psiElement instanceof PsiDirectory) {
      klass = PsiDirectoryNode.class;
    }
    else if (psiElement instanceof PsiPackage) {
      klass = PackageElementNode.class;
    }
    return klass;
  }

  private static Object getPsiElementNodeValue(PsiElement psiElement, Project project, Module module) {
    if (psiElement instanceof PsiPackage) {
      final PsiPackage psiPackage = (PsiPackage)psiElement;
      final PsiDirectory[] directories = psiPackage.getDirectories();
      if (directories.length > 0) {
        final VirtualFile firstDir = directories[0].getVirtualFile();
        final boolean isLibraryRoot = PackageUtil.isLibraryRoot(firstDir, project);
        return new PackageElement(module, psiPackage, isLibraryRoot);
      }
    }
    return psiElement;
  }

}