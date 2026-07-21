package tools.vitruv.pamola.vsum;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import mir.reactions.pamola2cad.Pamola2cadChangePropagationSpecification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
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
import tools.vitruv.pamola.cad.Parameter;

/**
 * Runnable demonstration of the pamola2cad reaction. Builds a V-SUM in the
 * folder ./demo-output, authors a small PAMoLa model, and prints the CAD model
 * after each change so you can watch the reaction propagate. The written
 * brake.pem and example.cad files can also be opened in the EMF editor.
 */
public final class Demo {

  public static void main(String[] args) throws Exception {
    Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
        .put("*", new XMIResourceFactoryImpl());

    Path dir = Path.of("demo-output").toAbsolutePath();
    deleteRecursively(dir); // start fresh each run (avoids reloading stale V-SUM state)
    dir.toFile().mkdirs();
    VirtualModel vsum = buildVsum(dir);

    // 1. register the PAMoLa domain root -> reaction creates the CAD_Model
    modify(view(vsum, Entity.class), v -> {
      Entity domain = PemFactory.eINSTANCE.createEntity();
      domain.setHasName("BrakeDomain");
      v.registerRoot(domain, URI.createFileURI(dir.resolve("brake.pem").toString()));
    });
    System.out.println("After registering BrakeDomain:");
    printCad(vsum);

    // 2. add a BrakeDisk classifier and a disk1 instance with minimumThicknessInMM
    modify(view(vsum, Entity.class), v -> {
      Entity domain = v.getRootObjects(Entity.class).iterator().next();
      Entity brakeDisk = PemFactory.eINSTANCE.createEntity();
      brakeDisk.setHasName("BrakeDisk");
      domain.getWards().add(brakeDisk);

      Entity disk1 = PemFactory.eINSTANCE.createEntity();
      disk1.setHasName("disk1");
      domain.getWards().add(disk1);
      disk1.setHasDeclaredType(brakeDisk); // -> reaction creates a CAD Namespace

      Attribute minThk = PemFactory.eINSTANCE.createAttribute();
      minThk.setHasName("minimumThicknessInMM");
      disk1.getWards().add(minThk);
      IntegerValueOccurrence iv = PemFactory.eINSTANCE.createIntegerValueOccurrence();
      minThk.getWards().add(iv);
      minThk.setHasValue(iv);
    });

    // 3. set the value to 25 -> reaction mirrors it into CAD
    modify(view(vsum, Entity.class), v -> value(v, "disk1", "minimumThicknessInMM").setValue(25));
    System.out.println("\nAfter setting minimumThicknessInMM = 25:");
    printCad(vsum);

    // 4. change 25 -> 24 -> CAD follows
    modify(view(vsum, Entity.class), v -> value(v, "disk1", "minimumThicknessInMM").setValue(24));
    System.out.println("\nAfter changing minimumThicknessInMM to 24:");
    printCad(vsum);

    System.out.println("\nModels written to: " + dir);
  }

  private static void printCad(VirtualModel vsum) {
    var roots = view(vsum, CAD_Model.class).getRootObjects(CAD_Model.class);
    if (roots.isEmpty()) {
      System.out.println("  (no CAD_Model yet)");
      return;
    }
    CAD_Model cad = roots.iterator().next();
    if (cad.getNamespaces().isEmpty()) {
      System.out.println("  CAD_Model exists, no namespaces yet");
    }
    cad.getNamespaces().forEach(ns -> {
      System.out.println("  Namespace " + ns.getId());
      for (Parameter p : ns.getParameters()) {
        String v = (p instanceof NumericParameter np) ? String.valueOf(np.getValue()) : "?";
        System.out.println("    - " + p.getName() + " = " + v);
      }
    });
  }

  private static IntegerValueOccurrence value(CommittableView v, String entity, String attr) {
    Entity domain = v.getRootObjects(Entity.class).iterator().next();
    Entity e = domain.getWards().stream()
        .filter(o -> o instanceof Entity en && entity.equals(en.getHasName()))
        .map(o -> (Entity) o).findFirst().orElseThrow();
    Attribute a = e.getWards().stream()
        .filter(o -> o instanceof Attribute at && attr.equals(at.getHasName()))
        .map(o -> (Attribute) o).findFirst().orElseThrow();
    return (IntegerValueOccurrence) a.getHasValue();
  }

  private static VirtualModel buildVsum(Path dir) throws java.io.IOException {
    InternalVirtualModel m = new VirtualModelBuilder()
        .withStorageFolder(dir)
        .withUserInteractorForResultProvider(
            new TestUserInteraction.ResultProvider(new TestUserInteraction()))
        .withChangePropagationSpecifications(new Pamola2cadChangePropagationSpecification())
        .buildAndInitialize();
    m.setChangePropagationMode(ChangePropagationMode.TRANSITIVE_CYCLIC);
    return m;
  }

  private static CommittableView view(VirtualModel vsum, Class<?> rootType) {
    var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
    selector.getSelectableElements().stream()
        .filter(rootType::isInstance)
        .forEach(it -> selector.setSelected(it, true));
    return selector.createView().withChangeDerivingTrait();
  }

  private static void modify(CommittableView v, Consumer<CommittableView> f) {
    f.accept(v);
    v.commitChanges();
  }

  private static void deleteRecursively(Path p) throws java.io.IOException {
    if (!java.nio.file.Files.exists(p)) return;
    try (var s = java.nio.file.Files.walk(p)) {
      s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
    }
  }

  private Demo() {}
}