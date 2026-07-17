package tools.vitruv.pamola.vsum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import mir.reactions.pamola2cad.Pamola2cadChangePropagationSpecification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.propagation.ChangePropagationMode;
import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;

import tools.vitruv.pamola.pem.Attribute;
import tools.vitruv.pamola.pem.Entity;
import tools.vitruv.pamola.pem.IntegerValueOccurrence;
import tools.vitruv.pamola.pem.PemFactory;
import tools.vitruv.pamola.cad.CAD_Model;
import tools.vitruv.pamola.cad.NumericParameter;

/**
 * End-to-end validation of the paper's claims against a real Vitruvius V-SUM:
 * the PEM is a registered domain, PEM instances are first-class participants,
 * and ONE generic value-mirror reaction propagates an integer attribute change
 * of a component instance into the CAD view.
 */
public class PamolaVsumTest {

  @BeforeAll
  static void setup() {
    Resource.Factory.Registry.INSTANCE
        .getExtensionToFactoryMap()
        .put("*", new XMIResourceFactoryImpl());
  }

  @Test
  void pemRootRegistersAndPropagatesToCad(@TempDir Path tempDir) throws IOException {
    VirtualModel vsum = createDefaultVirtualModel(tempDir);
    registerBrakeDomain(vsum, tempDir);
    Assertions.assertEquals(
        1, getDefaultView(vsum, List.of(Entity.class)).getRootObjects().size(),
        "PEM root Entity should be a V-SUM participant");
    Assertions.assertEquals(
        1, getDefaultView(vsum, List.of(CAD_Model.class)).getRootObjects().size(),
        "pamola2cad should have created a CAD_Model");
  }

  @Test
  void minimumThicknessMirrorsToCad(@TempDir Path tempDir) throws IOException {
    VirtualModel vsum = createDefaultVirtualModel(tempDir);
    registerBrakeDomain(vsum, tempDir);

    // Author, through a view: a BrakeDisk classifier and a disk1 instance typed
    // by it, carrying a reified minimumThicknessInMM attribute (initial 0).
    modifyView(
        getDefaultView(vsum, List.of(Entity.class)).withChangeDerivingTrait(),
        (CommittableView v) -> {
          Entity domain = v.getRootObjects(Entity.class).iterator().next();
          Entity brakeDisk = PemFactory.eINSTANCE.createEntity();
          brakeDisk.setHasName("BrakeDisk");
          domain.getWards().add(brakeDisk);

          Entity disk1 = PemFactory.eINSTANCE.createEntity();
          disk1.setHasName("disk1");
          domain.getWards().add(disk1);
          disk1.setHasDeclaredType(brakeDisk); // -> InstanceTyped -> Namespace

          Attribute minThk = PemFactory.eINSTANCE.createAttribute();
          minThk.setHasName("minimumThicknessInMM");
          disk1.getWards().add(minThk);
          IntegerValueOccurrence iv = PemFactory.eINSTANCE.createIntegerValueOccurrence();
          minThk.getWards().add(iv);
          minThk.setHasValue(iv);
        });

    // Now set the value to 25 through a view -> IntegerValueChanged fires.
    modifyView(
        getDefaultView(vsum, List.of(Entity.class)).withChangeDerivingTrait(),
        (CommittableView v) -> valueOf(v, "disk1", "minimumThicknessInMM").setValue(25));

    Assertions.assertEquals(
        25.0f, cadParam(vsum, "Minimum Thickness"), 0.001f,
        "CAD 'Minimum Thickness' should mirror the PEM value 25");

    // Change 25 -> 24 and assert the CAD parameter follows.
    modifyView(
        getDefaultView(vsum, List.of(Entity.class)).withChangeDerivingTrait(),
        (CommittableView v) -> valueOf(v, "disk1", "minimumThicknessInMM").setValue(24));

    Assertions.assertEquals(
        24.0f, cadParam(vsum, "Minimum Thickness"), 0.001f,
        "CAD 'Minimum Thickness' should follow the PEM change to 24");
  }

  // ---- helpers -------------------------------------------------------------

  private void registerBrakeDomain(VirtualModel vsum, Path tempDir) {
    modifyView(
        getDefaultView(vsum, List.of(Entity.class)).withChangeDerivingTrait(),
        (CommittableView v) -> {
          Entity domain = PemFactory.eINSTANCE.createEntity();
          domain.setHasName("BrakeDomain");
          v.registerRoot(domain, URI.createFileURI(tempDir.toString() + "/brake.pem"));
        });
  }

  private IntegerValueOccurrence valueOf(CommittableView v, String entityName, String attrName) {
    Entity domain = v.getRootObjects(Entity.class).iterator().next();
    Entity entity =
        domain.getWards().stream()
            .filter(o -> o instanceof Entity && entityName.equals(((Entity) o).getHasName()))
            .map(o -> (Entity) o)
            .findFirst().orElseThrow();
    Attribute attr =
        entity.getWards().stream()
            .filter(o -> o instanceof Attribute && attrName.equals(((Attribute) o).getHasName()))
            .map(o -> (Attribute) o)
            .findFirst().orElseThrow();
    return (IntegerValueOccurrence) attr.getHasValue();
  }

  private float cadParam(VirtualModel vsum, String paramName) {
    CAD_Model cad = getDefaultView(vsum, List.of(CAD_Model.class))
        .getRootObjects(CAD_Model.class).iterator().next();
    return cad.getNamespaces().stream()
        .flatMap(ns -> ns.getParameters().stream())
        .filter(p -> p instanceof NumericParameter && paramName.equals(p.getName()))
        .map(p -> ((NumericParameter) p).getValue())
        .findFirst().orElseThrow();
  }

  private InternalVirtualModel createDefaultVirtualModel(Path projectPath) throws IOException {
    InternalVirtualModel model =
        new VirtualModelBuilder()
            .withStorageFolder(projectPath)
            .withUserInteractorForResultProvider(
                new TestUserInteraction.ResultProvider(new TestUserInteraction()))
            .withChangePropagationSpecifications(new Pamola2cadChangePropagationSpecification())
            .buildAndInitialize();
    model.setChangePropagationMode(ChangePropagationMode.TRANSITIVE_CYCLIC);
    return model;
  }

  private View getDefaultView(VirtualModel vsum, Collection<Class<?>> rootTypes) {
    var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
    selector.getSelectableElements().stream()
        .filter(element -> rootTypes.stream().anyMatch(it -> it.isInstance(element)))
        .forEach(it -> selector.setSelected(it, true));
    return selector.createView();
  }

  private void modifyView(CommittableView view, Consumer<CommittableView> modificationFunction) {
    modificationFunction.accept(view);
    view.commitChanges();
  }
}
