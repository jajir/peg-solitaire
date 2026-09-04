# Senku

Behem zpracovani to potrebuje priblizne dvakrat az trikrat vice diskoveho prostoru.

Spolecne vysvetlivky k tabulkam:

* **n** - konkretni cislo tahu

* **PBP** - Possible Board Positions

* **Disk [GB]** - kolik diskoveho prostoru to po dokonceni skutecne zabiralo

* **Growth factor** – poměr `PBP(n) / PBP(n−1)`. Udává, kolikrát je aktuální vrstva větší nebo menší než předchozí.

* **Growth factor ratio** – poměr aktuálního a předchozího růstového faktoru. Hodnota nad `1` znamená zrychlování růstu, pod `1` zpomalování.

## English board

Hlavnim smyslem reseni bylo overit si, ze stavy pocitam spravne. Vysledky jsou na Wikipedii.

```plaintext
      ● ● ●
      ● ● ●
  ● ● ● ● ● ● ●
  ● ● ● ○ ● ● ●
  ● ● ● ● ● ● ●
      ● ● ●
      ● ● ●
```

Je nejmensi a ma 33 pozic. Pocet stavu lze snadno spocitat. Vysledna tabulka vypada takto:

| **n**     | **PBP**        |
| --------- | -------------- |
| **1**     | 1              |
| **2**     | 1              |
| **3**     | 2              |
| **4**     | 8              |
| **5**     | 39             |
| **6**     | 171            |
| **7**     | 719            |
| **8**     | 2 757          |
| **9**     | 9 751          |
| **10**    | 31 312         |
| **11**    | 89 927         |
| **12**    | 229 614        |
| **13**    | 517 854        |
| **14**    | 1 022 224      |
| **15**    | 1 753 737      |
| **16**    | 2 598 215      |
| **17**    | 3 312 423      |
| **18**    | 3 626 632      |
| **19**    | 3 413 313      |
| **20**    | 2 765 623      |
| **21**    | 1 930 324      |
| **22**    | 1 160 977      |
| **23**    | 600 372        |
| **24**    | 265 865        |
| **25**    | 100 565        |
| **26**    | 32 250         |
| **27**    | 8 688          |
| **28**    | 1 917          |
| **29**    | 348            |
| **30**    | 50             |
| **31**    | 7              |
| **32**    | 2              |
| **Total** | **23 475 688** |

## European

```plaintext
      ● ● ●
    ● ● ● ● ●
  ● ● ● ● ● ● ●
  ● ● ● ○ ● ● ●
  ● ● ● ● ● ● ●
    ● ● ● ● ●
      ● ● ●
```

Ma 37 pozic. Jde snadno spocitat. Vysledna tabulka vypada takto:

| **n**     | **PBP**     | Growth factor | Growth factor ratio |
| --------- | -----------:| -------------:| -------------------:|
| **1**     | 1           |               |                     |
| **2**     | 1           | 1,00          |                     |
| **3**     | 3           | 3,00          | 3,00                |
| **4**     | 15          | 5,00          | 1,67                |
| **5**     | 70          | 4,67          | 0,93                |
| **6**     | 341         | 4,87          | 1,04                |
| **7**     | 1 604       | 4,70          | 0,97                |
| **8**     | 6 950       | 4,33          | 0,92                |
| **9**     | 27 948      | 4,02          | 0,93                |
| **10**    | 102 261     | 3,66          | 0,91                |
| **11**    | 335 839     | 3,28          | 0,90                |
| **12**    | 984 710     | 2,93          | 0,89                |
| **13**    | 2 558 220   | 2,60          | 0,89                |
| **14**    | 5 858 375   | 2,29          | 0,88                |
| **15**    | 11 789 357  | 2,01          | 0,88                |
| **16**    | 20 795 984  | 1,76          | 0,88                |
| **17**    | 32 106 854  | 1,54          | 0,88                |
| **18**    | 43 386 122  | 1,35          | 0,88                |
| **19**    | 51 362 742  | 1,18          | 0,88                |
| **20**    | 53 371 113  | 1,04          | 0,88                |
| **21**    | 48 801 369  | 0,91          | 0,88                |
| **22**    | 39 361 771  | 0,81          | 0,88                |
| **23**    | 28 039 820  | 0,71          | 0,88                |
| **24**    | 17 646 892  | 0,63          | 0,88                |
| **25**    | 9 813 533   | 0,56          | 0,88                |
| **26**    | 4 808 524   | 0,49          | 0,88                |
| **27**    | 2 068 047   | 0,43          | 0,88                |
| **28**    | 776 914     | 0,38          | 0,87                |
| **29**    | 253 243     | 0,33          | 0,87                |
| **30**    | 70 245      | 0,28          | 0,85                |
| **31**    | 16 690      | 0,24          | 0,86                |
| **32**    | 3 350       | 0,20          | 0,84                |
| **33**    | 536         | 0,16          | 0,80                |
| **34**    | 62          | 0,12          | 0,72                |
| **35**    | 11          | 0,18          | 1,53                |
| **36**    | 0           | 0,00          | 0,00                |
| **Total** | 374 349 517 |               |                     |

## Senku

```plaintext
        ● ● ●
        ● ● ●
      ● ● ● ● ●
  ● ● ● ● ● ● ● ● ●
  ● ● ● ● ○ ● ● ● ●
  ● ● ● ● ● ● ● ● ●
      ● ● ● ● ●
        ● ● ●
        ● ● ●
```

Tady je vypocet vyrazne komplikovanejsi, protoze deska ma 49 pozic.

| **n**     | **PBP**        | Growth factor | Growth factor ratio | **Disk [GB] 1)** | **Time [hour] 2)** | **Ingestion speed [mil. stavu/min] 3)** |
| --------- | --------------:| -------------:| -------------------:| ----------------:| ------------------:| ---------------------------------------:|
| **1**     | 1              |               |                     |                  |                    |                                         |
| **2**     | 1              | 1,00          |                     |                  |                    |                                         |
| **3**     | 4              | 4,00          | 4,00                |                  |                    |                                         |
| **4**     | 19             | 4,75          | 1,19                |                  |                    |                                         |
| **5**     | 105            | 5,53          | 1,16                |                  |                    |                                         |
| **6**     | 579            | 5,51          | 1,00                |                  |                    |                                         |
| **7**     | 3 097          | 5,35          | 0,97                |                  |                    |                                         |
| **8**     | 15 787         | 5,10          | 0,95                |                  |                    |                                         |
| **9**     | 76 830         | 4,87          | 0,95                |                  |                    |                                         |
| **10**    | 353 018        | 4,59          | 0,94                |                  |                    |                                         |
| **11**    | 1 523 417      | 4,32          | 0,94                |                  |                    |                                         |
| **12**    | 6 151 839      | 4,04          | 0,94                |                  |                    |                                         |
| **13**    | 23 159 410     | 3,76          | 0,93                |                  |                    |                                         |
| **14**    | 81 020 851     | 3,50          | 0,93                |                  |                    |                                         |
| **15**    | 262 677 951    | 3,24          | 0,93                | 1                |                    |                                         |
| **16**    | 786 896 629    | 3,00          | 0,92                | 3                |                    |                                         |
| **17**    | 2 171 225 023  | 2,76          | 0,92                | 10               | 1                  | 36                                      |
| **18**    | 5 499 266 920  | 2,53          | 0,92                | 25               | 3                  | 31                                      |
| **19**    | 12 736 742 032 | 2,32          | 0,91                | 58               | 8                  | 27                                      |
| **20**    | 26 859 538 675 | 2,11          | 0,91                | 120              | 16                 | 28                                      |
| **21**    | 51 328 644 589 | 1,91          | 0,91                | 227              | 37                 | 23                                      |
| **22**    |                |               |                     |                  |                    |                                         |
| **23**    |                |               |                     |                  |                    |                                         |
| **24**    |                |               |                     |                  |                    |                                         |
| **25**    |                |               |                     |                  |                    |                                         |
| **26**    |                |               |                     |                  |                    |                                         |
| **27**    |                |               |                     |                  |                    |                                         |
| **28**    |                |               |                     |                  |                    |                                         |
| **29**    |                |               |                     |                  |                    |                                         |
| **30**    |                |               |                     |                  |                    |                                         |
| **31**    |                |               |                     |                  |                    |                                         |
| **32**    |                |               |                     |                  |                    |                                         |
| **33**    |                |               |                     |                  |                    |                                         |
| **34**    |                |               |                     |                  |                    |                                         |
| **35**    |                |               |                     |                  |                    |                                         |
| **36**    |                |               |                     |                  |                    |                                         |
| **37**    |                |               |                     |                  |                    |                                         |
| **38**    |                |               |                     |                  |                    |                                         |
| **39**    |                |               |                     |                  |                    |                                         |
| **40**    |                |               |                     |                  |                    |                                         |
| **41**    |                |               |                     |                  |                    |                                         |
| **42**    |                |               |                     |                  |                    |                                         |
| **43**    |                |               |                     |                  |                    |                                         |
| **44**    |                |               |                     |                  |                    |                                         |
| **45**    |                |               |                     |                  |                    |                                         |
| **46**    |                |               |                     |                  |                    |                                         |
| **47**    |                |               |                     |                  |                    |                                         |
| **48**    |                |               |                     |                  |                    |                                         |
| **Total** |                |               |                     |                  |                    |                                         |

**Disk 1)** - kolik GiB to na konci kazdeho kola zabiralo na disku

**Time 2)** - jak dlouho v hodinach trval vypocet daneho kola

**Ingestion speed 3)** - pocet milionu stavu ulozenych za jednu minutu

V celkovych casech je zapocitano nekolik minut navic. Po tom, co skonci zapisovani stavu, je treba nechat dobehnout posledni upravy souboru; teprve potom jsou data dostupna pro cteni.

Vypocet jsem tedy nedokoncil, ale mam dojem, ze jsem se dostal blizko. Kdybych mel dostatecne velky disk, mohl bych si na vysledek pockat.

Zaroven mam dojem, ze vim, proc se ingestion speed meni, a neskromne se domnivam, ze bych tuto rychlost dokazal v tomto konkretnim pripade zvysit o nekolik desitek procent.

## Co lze dal vylepsovat

Kdyz jednoduse extrapoluji hodnotu „Growth factor ratio“ az do konce, vidim, ze by se cely vypocet mel vejit na SSD disk o kapacite 6 TB. Vypocet by vsak trval nekolik mesicu. Odhad je uveden v tabulce nize.

Hodnotu „Ingestion speed“ lze dale zvysovat. Vidim nekolik moznosti:

1. Optimalizovat pocet vlaken; nekdy jsou vsechna blokovana.

2. Optimalizovat velikost cache, diskove bloky a dalsi behova nastaveni.

3. Paralelizovat vypocet na vice uzlu.

4. Vylepsit zpusob ukladani stavu.

5. Vylepsit kompresi (ted pouzivam Snappy/ZIP).

Osobne se mi nechce realizovat bod 3, protoze mam dojem, ze sem nepatri. Ostatni body jsou realizovatelne, ale nevim, jestli se do nich pustim.

### Odhad poctu stavu

Vzal jsem skutecne hodnoty a extrapoloval je za predpokladu, ze 'Growth factor ratio' zustane priblizne konstantni. Hodnotu 'Growth factor' jsem urcil z 'Growth factor ratio' a z 'Growth factor' jsem odhadl pocet stavu v danem `n`. Od radku 22 vcetne jsou v tabulce odhady. Proto mame v kole 48 prave 175 stavu, coz realne neni mozne.

| **n**     | **PBP**             | Growth factor | Growth factor ratio | **Disk [GB]** | **Time [hour]** |
| --------- | -------------------:| -------------:| -------------------:| -------------:| ---------------:|
| **1**     | 1                   |               |                     |               |                 |
| **2**     | 1                   | 1,00          |                     |               |                 |
| **3**     | 4                   | 4,00          | 4,00                |               |                 |
| **4**     | 19                  | 4,75          | 1,19                |               |                 |
| **5**     | 105                 | 5,53          | 1,16                |               |                 |
| **6**     | 579                 | 5,51          | 1,00                |               |                 |
| **7**     | 3 097               | 5,35          | 0,97                |               |                 |
| **8**     | 15 787              | 5,10          | 0,95                |               |                 |
| **9**     | 76 830              | 4,87          | 0,95                |               |                 |
| **10**    | 353 018             | 4,59          | 0,94                |               |                 |
| **11**    | 1 523 417           | 4,32          | 0,94                |               |                 |
| **12**    | 6 151 839           | 4,04          | 0,94                |               |                 |
| **13**    | 23 159 410          | 3,76          | 0,93                |               |                 |
| **14**    | 81 020 851          | 3,50          | 0,93                |               |                 |
| **15**    | 262 677 951         | 3,24          | 0,93                | 1             |                 |
| **16**    | 786 896 629         | 3,00          | 0,92                | 3             | 1               |
| **17**    | 2 171 225 023       | 2,76          | 0,92                | 9             | 1               |
| **18**    | 5 499 266 920       | 2,53          | 0,92                | 22            | 3               |
| **19**    | 12 736 742 032      | 2,32          | 0,91                | 51            | 8               |
| **20**    | 26 859 538 675      | 2,11          | 0,91                | 107           | 16              |
| **21**    | 51 328 644 589      | 1,91          | 0,91                | 205           | 43              |
| **22**    | *98 089 166 282*    | *1,72*        | *0,90*              | *392*         | *82*            |
| **23**    | *168 703 774 608*   | *1,55*        | *0,90*              | *675*         | *141*           |
| **24**    | *261 138 596 455*   | *1,39*        | *0,90*              | *1 045*       | *218*           |
| **25**    | *363 797 609 420*   | *1,25*        | *0,90*              | *1 455*       | *303*           |
| **26**    | *456 132 613 773*   | *1,13*        | *0,90*              | *1 825*       | *380*           |
| **27**    | *514 712 742 371*   | *1,02*        | *0,90*              | *2 059*       | *429*           |
| **28**    | *522 734 571 577*   | *0,91*        | *0,90*              | *2 091*       | *436*           |
| **29**    | *477 793 279 329*   | *0,82*        | *0,90*              | *1 911*       | *398*           |
| **30**    | *393 044 170 343*   | *0,74*        | *0,90*              | *1 572*       | *328*           |
| **31**    | *290 994 775 086*   | *0,67*        | *0,90*              | *1 164*       | *242*           |
| **32**    | *193 897 197 733*   | *0,60*        | *0,90*              | *776*         | *162*           |
| **33**    | *116 278 757 754*   | *0,54*        | *0,90*              | *465*         | *97*            |
| **34**    | *62 758 382 775*    | *0,49*        | *0,90*              | *251*         | *52*            |
| **35**    | *30 484 958 871*    | *0,44*        | *0,90*              | *122*         | *25*            |
| **36**    | *13 327 294 437*    | *0,39*        | *0,90*              | *53*          | *11*            |
| **37**    | *5 243 736 755*     | *0,35*        | *0,90*              | *21*          | *4*             |
| **38**    | *1 856 873 333*     | *0,32*        | *0,90*              | *7*           | *2*             |
| **39**    | *591 788 044*       | *0,29*        | *0,90*              | *2*           |                 |
| **40**    | *169 743 285*       | *0,26*        | *0,90*              | *1*           |                 |
| **41**    | *43 818 906*        | *0,23*        | *0,90*              |               |                 |
| **42**    | *10 180 590*        | *0,21*        | *0,90*              |               |                 |
| **43**    | *2 128 761*         | *0,19*        | *0,90*              |               |                 |
| **44**    | *400 611*           | *0,17*        | *0,90*              |               |                 |
| **45**    | *67 852*            | *0,15*        | *0,90*              |               |                 |
| **46**    | *10 343*            | *0,14*        | *0,90*              |               |                 |
| **47**    | *1 419*             | *0,12*        | *0,90*              |               |                 |
| **48**    | *175*               | *0,11*        | *0,90*              |               |                 |
| **Total** | *4 071 563 937 665* |               |                     |               |                 |

## Zdroje

[Peg Solitaire at wikipedia](https://en.wikipedia.org/wiki/Peg_solitaire)
