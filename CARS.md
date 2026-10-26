# Cars

## Tested with AutoSpace

The fork is tested in a 2019 Nissan Altima, which works with AutoSpace.

| Car | Year | Head unit |
|---|---|---|
| Nissan Altima | 2019 | — |

## Reported for upstream Gearslip

The table below is upstream-era data: cars reported working with
Gearslip, the project AutoSpace forked from. It has NOT been
re-verified car by car for AutoSpace, whose protocol code is inherited
from Gearslip. Treat it as the best available compatibility signal,
not as fork-verified fact.

The year is the one the car reports. Some cars report the year of the head unit instead,
and some don't say.

| Car | Year | Head unit |
|---|---|---|
| Audi | | MIB2+ (Alpine) |
| Audi | | Harman |
| Chevrolet, with myChevrolet | 2019, 2020, 2021 | myChevrolet |
| Chevrolet, with MyLink | 2017 | MyLink |
| Dacia | | Media Nav (LG ULC 4.1) |
| Dacia | | LG ULC 4.5 |
| Ford | 2018 | SYNC 3.0 (Panasonic) |
| Ford Fiesta | 2019 | SYNC 3.2 (Panasonic) |
| Ford Fusion | 2018 | SYNC 3.1 (Panasonic) |
| Jeep Cherokee | 2019 | Uconnect 8.4 (Delphi) |
| MG | 2019 | Desay SV |
| Ram 1500 | 2018 | Uconnect 8.4 (Panasonic) |
| Škoda | 2018 | MIB Standard2 (TechniSat) |
| Subaru Outback | 2020 | Denso |
| Toyota RAV4 | 2020 | Pioneer T1 |
| Volkswagen | 2018 | MIB Standard2 (Delphi) |
| Volkswagen | 2024 | ICAS3 |
| Volvo XC90 | 2022 | SPA Plus (Mitsubishi Electric) |
| Wuling | 2021 | Desay SV |
| [LIVI](https://github.com/f-io/LIVI), the open-source head unit | | |

## Not working yet (upstream reports)

| Car | Year | What happens |
|---|---|---|
| Honda | 2016 | The connection drops before Gearslip reaches the screen. |
| Honda | 2019 | The connection drops before Gearslip reaches the screen. |
| Mitsubishi Attrage | 2020 | The connection drops before Gearslip reaches the screen. |

Some cars reject the host's certificate before they say what they are, so they can't appear
here by name. Most of these are from around 2020 or later.

## Add your car

AutoSpace has no usage-notes pipeline — the feature that fed the old list
was removed from the fork — so new entries come from issue reports, not
from notes. Open an issue with your car's make, model, and year, whether
the head unit is original, and the log from the drive (phone app:
Settings, then Logs, then Share log). If your car is missing or listed
wrongly, open an issue.
