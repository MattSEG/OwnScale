# Models and assumptions

All examples below are synthetic. No personal readings are distributed with this repository.

## Direct scans

The Bluetooth protocol supplies weight and segment impedance. `BodyFatModel` implements an experimental whole-body/TBW calculation informed by Blue2Scale's protocol documentation. It is not Hume's proprietary algorithm and has not been validated against a reference method.

Personal tuning applies:

```text
individual BF% = anchor + sensitivity × (raw model BF% − reference model BF%)
```

The anchor is user-selected and must not be mistaken for a measured calibration. Sensitivity is 0–2; 0 holds the chosen anchor constant. Height/sex compatibility is checked before applying a tuning version. The public build initializes its first baseline to the uncalibrated model result; it does not preload a personal percentage. An invalid model output is not made valid by clamping.

## Time-aware stabilization

Default response time is 72 hours; choices are off, 24, 72 or 168 hours.

```text
alpha = 1 − exp(−elapsed hours / response hours)
filtered = previous filtered + alpha × (new value − previous filtered)
```

Closely spaced repeats have little effect, while measurements separated by days have greater influence. The response time is an exponential time constant, not a promise that changes take exactly three days. Long gaps reduce smoothing substantially. The app does not invent measurements on missing days.

For OwnScale scans, the filter operates on compatible raw model values. The filtered reference is rebased to the selected anchor before sensitivity is applied. Excluded readings do not influence the filter. The separate graph line can be hidden without changing the calculation used on Today or in Health Connect.

Weight and waist graphs separately use a trailing seven-calendar-day, equally weighted daily-mean trend. That graph treatment is distinct from body-fat stabilization.

## Historical weight-informed reconstruction

Hume history imported through Health Connect lacks raw impedance. Its BF% is preserved for comparison but is not an input into the reconstruction.

Let `Wr` be the reference scan's measured weight, `Pr` its assumed BF%, `Wh` the historical weight and `q` the fraction of weight difference attributed to fat.

```text
reference fat mass = Wr × Pr / 100
historical fat mass = reference fat mass + q × (Wh − Wr)
historical BF% = 100 × historical fat mass / Wh
```

Default `q = 0.75`: 75% fat and 25% lean. It is adjustable from 0 to 1 and saved with each tuning version. The same allocation is applied in both directions; this deliberately simple model cannot infer different physiology during separate gain/loss phases.

Synthetic example: a reference of **80 kg at 20%** means 16 kg of assumed fat. At a historical weight of **90 kg**, the 75/25 scenario assigns 7.5 kg of the 10 kg difference to fat, yielding **26.11%**. At 100% fat allocation it yields **28.89%**. These are conditional scenarios, not recovered measurements.

- Individual reconstruction uses each original historical weight.
- Stabilized reconstruction first filters the historical Hume weights with the elapsed-time filter, then applies the formula. It does not blend Hume percentages with OwnScale impedance estimates.
- The anchor uses the selected reference scan's actual measured weight.
- Missing weights, unavailable references or impossible results (outside 0–100%) produce no estimate.
- Originals remain in observations, details, graph comparison and CSV exports.
- Changing the allocation changes the historical view, not raw data. Imported Hume records are not rewritten in Health Connect.

The superseded fixed-offset field remains in the schema for backup provenance but is no longer used by the reconstruction.

## What this cannot establish

Neither body weight nor an assumed anchor tells us the actual fat/lean split. Water, glycogen and other non-fat changes can affect weight and impedance. Stabilization reduces short-term variation but cannot correct systematic measurement bias. Use independent reference evidence if you want to assess accuracy. Do not use these estimates as a diagnosis or a substitute for clinical assessment.
