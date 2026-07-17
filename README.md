# PAMoLa in Vitruvius

This repository contains a Vitruvius project that uses the PAMoLa Ecore Model
(PEM) as a participant metamodel and keeps a PAMoLa model consistent with a CAD
model using the Reactions language. It uses the brake-system case study.

The project is built on the Vitruvius Methodologist Template
(https://github.com/vitruv-tools) and follows the same structure.

## Metamodels

- `model/src/main/ecore/pem.ecore` — the PAMoLa Ecore Model (PEM). Every PAMoLa
  model is an instance of this single metamodel.
- `model/src/main/ecore/cad.ecore` — the CAD metamodel from the brake-system
  case study.

The `.genmodel` files next to them are used to generate the Java model code.

## Reactions

The consistency rules are in
`consistency/src/main/reactions/tools/vitruv/pamola/consistency/pamola2cad.reactions`.

| Reaction | Fires when | Effect |
|---|---|---|
| `PamolaRootInserted` | the PAMoLa domain root is registered | creates the `CAD_Model` |
| `InstanceTyped` | an entity gets a declared type (an instance) | creates a `Namespace` for it |
| `IntegerValueChanged` | an integer attribute value changes | writes the value to the CAD parameter |

Unlike the case-study reactions, which are written per attribute of each brake
component, `IntegerValueChanged` is a single reaction that reacts to the change
of any integer attribute of any component, because the trigger is on the PEM
structure rather than on a domain type:

```
reaction IntegerValueChanged {
  after attribute replaced at pamola::IntegerValueOccurrence[value]
  call mirrorIntegerValue(affectedEObject)
}

routine mirrorIntegerValue(pamola::IntegerValueOccurrence iv) {
  match {
    val ns = retrieve cad::Namespace corresponding to iv.eContainer.eContainer
  }
  create {
    val np = new cad::NumericParameter
  }
  update {
    val attr = iv.eContainer as Attribute
    val owner = attr.eContainer as Entity
    // per-feature knowledge resolved by the feature's fully qualified name
    val featureFqn = owner.hasDeclaredType.hasName + "." + attr.hasName
    val cadName = switch (featureFqn) {
      case "BrakeDisk.minimumThicknessInMM": "Minimum Thickness"
      case "BrakeDisk.brakeDiskThicknessInMM": "Brake Disk Thickness"
      case "BrakeDisk.diameterInMM": "Diameter"
      default: attr.hasName
    }
    var p = ns.parameters.findFirst[name == cadName] as NumericParameter
    if (p === null) {
      p = np
      p.name = cadName
      p.unit = Unit.MM
      ns.parameters.add(p)
    }
    p.setValue(iv.value)
  }
}
```

Note that in the PEM an attribute value is reified: an `Attribute` points via
`hasValue` to a `DataValue` (here an `IntegerValueOccurrence`), which carries the
actual number. The trigger is therefore on `IntegerValueOccurrence[value]`. The
string and boolean occurrences are handled the same way.

## What it does

`vsum/src/test/java/tools/vitruv/pamola/vsum/PamolaVsumTest.java` builds a V-SUM
with the PAMoLa and CAD models and checks the propagation. It registers a
PAMoLa domain, adds a `BrakeDisk` classifier and a `disk1` instance in one PEM
model, gives the disk a `minimumThicknessInMM` attribute, and changes its value.
The corresponding CAD parameter follows the change.

## Running it

With Maven (JDK 17 or later):

```
mvn install          # build all modules and generate the model code
mvn -pl vsum test    # run the test
```

In Eclipse (with EMF and the Vitruvius setup, see
https://github.com/vitruv-tools):

1. Import the four modules as existing Maven projects.
2. To generate the model code, open `model/src/main/ecore/pem.genmodel` (and
   `cad.genmodel`) and choose *Generate Model Code*, or run
   `model/workflow/generate.mwe2` as an MWE2 Workflow.
3. The reactions are compiled by the build in the `consistency` module.
4. Run `PamolaVsumTest` in the `vsum` module as a JUnit test.

## Sources

- PEM: PAMoLa kernel-core.
- CAD metamodel and the original per-attribute reactions: brake-system case
  study (ASEW 2025).
- Project structure: Vitruvius Methodologist Template.
