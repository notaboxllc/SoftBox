# Future Work Ideas

Ideas recorded here are not active tasks or established conclusions. Each entry should state:
- scientific question;
- motivation or experimental connection;
- minimum model capability required;
- suggested first assay;
- dependencies;
- status.

## Experimental-comparison opportunities

### S2-mediated backbone motion during a persistent head attachment

Scientific question:
Can the asymmetric mechanics of the explicit S2 region allow a myosin filament backbone to translate substantially while an actin-bound head remains comparatively fixed?

Motivation:
Brizendine et al. (2021) provides a future mechanical validation target. The relevant comparison is not their schematic representation of myosin, but their evidence that proximal-S2 compliance permits motion of the rod/backbone relative to an actin-bound head.

Required model capability:
- a rigid thick-filament proxy carrying multiple S2 anchors, or
- a myosin minifilament with a shared mechanical backbone.

Suggested first assay:
Prescribe quasi-static translation of a rigid backbone while one head remains attached to a fixed actin site. Measure:
- backbone-anchor displacement relative to the actin site;
- head displacement relative to actin;
- S2 axial extension;
- S2 transverse bending;
- bend and stretch energies;
- force buildup;
- maximum displacement before detachment.

Suggested dynamic assay:
With normal chemistry and turnover restored, measure the distribution of backbone travel during each continuous attachment and its dependence on attachment lifetime, load, and S2 geometry.

Dependencies:
- shared thick-filament/backbone object;
- explicit reactions from S2 anchors to the backbone;
- attachment-resolved backbone-relative displacement telemetry.

Relationship to current work:
Potentially informative for converter-twirling efficiency because the same S2 compliance may absorb circumferential converter motion, but this is an independent study and should not interrupt the current basic twirling investigation.

Status:
Deferred.


## Scientific communication and model figures

### Real-myosin-to-SoftBox structural mapping figure

Goal:
Create a demonstrative figure that maps implemented SoftBox motor elements directly onto corresponding real myosin structures.

The current figure is not considered satisfactory and should not be treated as final.

The replacement should distinguish:
- real molecular structures;
- reduced mechanical bodies;
- simulated points and coordinates;
- nonphysical visualization aids.

Required mapping:
- actin-binding interface ↔ F8 point;
- motor domain ↔ modeled head body;
- converter ↔ converter joint and rotating converter geometry;
- lever arm/light-chain region ↔ neck–lever element;
- head–S2 junction;
- proximal free S2 ↔ explicit beam;
- distal rod/thick-filament attachment ↔ S2 anchor;
- SoftBox points P, C, xH, and xF8.

The figure should show:
- axial, radial, and circumferential directions;
- actin surface and discrete binding site;
- native converter trajectory;
- locally skewed converter trajectory;
- free S2 length;
- converter/F8 eccentricity;
- which quantities are physical dimensions versus model abstractions.

Potential source material:
Use structural and molecular-dynamics publications as references for anatomy and scale, but generate the final schematic specifically from the implemented SoftBox geometry rather than reproducing a published cartoon.

Status:
Deferred; revisit after the current converter-twirling geometry analysis clarifies which motor dimensions are most load-bearing.

