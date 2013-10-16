package org.activiti.designer.kickstart.eclipse.editor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;

import org.activiti.designer.kickstart.eclipse.ui.ActivitiEditorContextMenuProvider;
import org.activiti.designer.kickstart.eclipse.util.FileService;
import org.activiti.designer.util.editor.KickstartProcessMemoryModel;
import org.activiti.designer.util.editor.ModelHandler;
import org.activiti.workflow.simple.converter.json.SimpleWorkflowJsonConverter;
import org.activiti.workflow.simple.definition.StepDefinition;
import org.activiti.workflow.simple.definition.WorkflowDefinition;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.command.BasicCommandStack;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gef.ContextMenuProvider;
import org.eclipse.gef.GraphicalViewer;
import org.eclipse.graphiti.mm.pictograms.Diagram;
import org.eclipse.graphiti.ui.editor.DiagramEditor;
import org.eclipse.graphiti.ui.editor.DiagramEditorInput;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;

public class KickstartProcessDiagramEditor extends DiagramEditor {

  private static GraphicalViewer activeGraphicalViewer;

  private KickstartProcessChangeListener activitiBpmnModelChangeListener;

  private TransactionalEditingDomain transactionalEditingDomain;

  public KickstartProcessDiagramEditor() {
    super();
  }

  @Override
  protected void registerBusinessObjectsListener() {
    activitiBpmnModelChangeListener = new KickstartProcessChangeListener(this);

    final TransactionalEditingDomain ted = getEditingDomain();
    ted.addResourceSetListener(activitiBpmnModelChangeListener);
  }

  @Override
  public TransactionalEditingDomain getEditingDomain() {
    TransactionalEditingDomain ted = super.getEditingDomain();

    if (ted == null) {
      ted = transactionalEditingDomain;
    }

    return ted;
  }

  @Override
  public void init(IEditorSite site, IEditorInput input) throws PartInitException {
    IEditorInput finalInput = null;

    try {
      if (input instanceof KickstartDiagramEditorInput) {
        finalInput = input;
      } else {
        finalInput = createNewDiagramEditorInput(input);
      }
    } catch (CoreException exception) {
      exception.printStackTrace();
    }

    super.init(site, finalInput);
  }

  private KickstartDiagramEditorInput createNewDiagramEditorInput(final IEditorInput input) throws CoreException {

    final IFile dataFile = FileService.getDataFileForInput(input);

    // now generate the temporary diagram file
    final IPath dataFilePath = dataFile.getFullPath();

    // get or create the corresponding temporary folder
    final IFolder tempFolder = FileService.getOrCreateTempFolder(dataFilePath);

    // finally get the diagram file that corresponds to the data file
    final IFile diagramFile = FileService.getTemporaryDiagramFile(dataFilePath, tempFolder);

    // Create new temporary diagram file
    KickstartProcessDiagramCreator creator = new KickstartProcessDiagramCreator();

    return creator.creatProcessDiagram(dataFile, diagramFile, this, null, false);
  }

  @Override
  public void doSave(IProgressMonitor monitor) {
    super.doSave(monitor);

    final KickstartDiagramEditorInput adei = (KickstartDiagramEditorInput) getEditorInput();

    try {
      final IFile dataFile = adei.getDataFile();
      final String diagramFileString = dataFile.getLocationURI().getPath();

      KickstartProcessMemoryModel model = ModelHandler.getKickstartProcessModel(EcoreUtil.getURI(getDiagramTypeProvider().getDiagram()));

      SimpleWorkflowJsonConverter converter = new SimpleWorkflowJsonConverter();
      File objectsFile = new File(diagramFileString);
      FileWriter writer = new FileWriter(objectsFile);
      converter.writeWorkflowDefinition(model.getWorkflowDefinition(), writer);
      writer.close();
      
      dataFile.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);

    } catch (Exception e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    ((BasicCommandStack) getEditingDomain().getCommandStack()).saveIsDone();
    updateDirtyState();
  }

  @Override
  public boolean isDirty() {
    TransactionalEditingDomain editingDomain = getEditingDomain();
    // Check that the editor is not yet disposed
    if (editingDomain != null && editingDomain.getCommandStack() != null) {
      return ((BasicCommandStack) editingDomain.getCommandStack()).isSaveNeeded();
    }
    return false;
  }

  @Override
  protected void setInput(IEditorInput input) {
    super.setInput(input);

    final KickstartDiagramEditorInput adei = (KickstartDiagramEditorInput) input;
    final IFile dataFile = adei.getDataFile();

    final KickstartProcessMemoryModel model = new KickstartProcessMemoryModel(getDiagramTypeProvider().getFeatureProvider(), dataFile);
    ModelHandler.addModel(EcoreUtil.getURI(getDiagramTypeProvider().getDiagram()), model);

    String filePath = dataFile.getLocationURI().getPath();
    File kickstartProcessFile = new File(filePath);
    try {
      if (kickstartProcessFile.exists() == false) {
        model.setWorkflowDefinition(new WorkflowDefinition());
        kickstartProcessFile.createNewFile();
        dataFile.refreshLocal(IResource.DEPTH_INFINITE, null);
      } else {
        FileInputStream fileStream = new FileInputStream(kickstartProcessFile);
        SimpleWorkflowJsonConverter converter = new SimpleWorkflowJsonConverter();
        WorkflowDefinition definition = null;
        try {
          definition = converter.readWorkflowDefinition(fileStream);
        } catch(Exception e) {
          definition = new WorkflowDefinition();
        }
        model.setWorkflowDefinition(definition);

        BasicCommandStack basicCommandStack = (BasicCommandStack) getEditingDomain().getCommandStack();

        if (input instanceof DiagramEditorInput) {

          basicCommandStack.execute(new RecordingCommand(getEditingDomain()) {

            @Override
            protected void doExecute() {
              importDiagram(model);
              
              // Hide the grid
              getDiagramTypeProvider().getDiagram().setGridUnit(-1);
            }
          });
        }
        basicCommandStack.saveIsDone();
        basicCommandStack.flush();
      }
      
      model.setInitialized(true);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void importDiagram(final KickstartProcessMemoryModel model) {
    final Diagram diagram = getDiagramTypeProvider().getDiagram();
    diagram.setActive(true);

    getEditingDomain().getCommandStack().execute(new RecordingCommand(getEditingDomain()) {

      @Override
      protected void doExecute() {
        //addContainerElement(diagram, model);
              
        for (StepDefinition step : model.getWorkflowDefinition().getSteps()) {
          // draw step
        }
      }
    });
  }

  @Override
  protected ContextMenuProvider createContextMenuProvider() {
    return new ActivitiEditorContextMenuProvider(getGraphicalViewer(), getActionRegistry(), getDiagramTypeProvider());
  }

  public static GraphicalViewer getActiveGraphicalViewer() {
    return activeGraphicalViewer;
  }

  @Override
  public void dispose() {
    super.dispose();

    final KickstartDiagramEditorInput adei = (KickstartDiagramEditorInput) getEditorInput();

    ModelHandler.getKickstartProcessModel(EcoreUtil.getURI(getDiagramTypeProvider().getDiagram()));
    KickstartProcessDiagramCreator.dispose(adei.getDiagramFile());
  }
}