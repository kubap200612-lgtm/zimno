# BK Analityk (Fabric 1.21.4)

Dodatek do **BK-Rynek 3.0**: analizuje ceny przedmiotów z listy BK, rysuje wykresy i sam ustawia ceny skupu (z 3% podatkiem).

## Instalacja
1. Wrzuć repo na GitHuba → zakładka **Actions** → workflow `build` → pobierz artefakt `bk-analityk` (albo lokalnie `./gradlew build`, plik w `build/libs/`).
2. Do `mods/` wrzuć `bk-analityk-1.1.0.jar` **obok** BK-Rynek 3.0 i Fabric API.
3. Jeśli masz stary dodatek „Rynek Analiza”, który też zmienia ceny w BK — wyłącz go, żeby oba nie walczyły o te same ceny.

## Obsługa
| | |
|---|---|
| **J** | okno analizy (lista, wykres, sugestie, ustawienia) |
| **K** | pełny skan rynku (krótka pauza BK) / przerwij |
| `/analityk`, `/analityk skan`, `/analityk auto on/off` | to samo z czatu |

Klawisze zmienisz w *Opcje → Sterowanie → BK Analityk*.

## Jak to działa — analiza na żywo (bez pauz BK)
Na Anarchii BK-Rynek cały czas stoi na **1. stronie rynku posortowanej „Najnowsze”**, a serwer na bieżąco dosyła zmiany.
BK Analityk czyta dokładnie ten sam widok, w trakcie pracy BK, i porównuje kolejne stany strony:
- **nowa oferta** na górze → zapis ceny (podaż) i dopisanie do listy wiszących ofert,
- **oferta zniknęła ze środka strony** → ktoś ją kupił → zapis ceny i czasu, po jakim zeszła (popyt);
  zakupy samego BK są rozpoznawane i nie liczą się jako popyt,
- **oferta zepchnięta z końca strony** → dalej wisi na rynku, zostaje na liście (domyślnie do 6 h).

Z tej listy co chwilę liczona jest najtańsza oferta, a **co 5 minut** ceny w BK są korygowane — BK ani na moment nie staje.
Rozpoznawanie przedmiotów robi silnik BK, więc analiza dotyczy dokładnie tego, co BK kupuje.
Twoje własne oferty i pojedyncze „pomyłkowe” oferty (< 50% typowej ceny) nie zaniżają analizy.

**Pełny skan (klawisz K)** jest opcjonalny: na kilkanaście sekund wstrzymuje BK, przechodzi przez wszystkie strony
i zapisuje komplet wiszących ofert. Przydaje się raz na jakiś czas, bo analiza na żywo nie widzi starych ofert,
które wisiały, zanim BK zaczął pracę. Można go włączyć automatycznie raz po starcie BK („Kalibracja po starcie BK”)
albo cyklicznie („Pełny skan co … min”) — domyślnie oba są wyłączone.

### Ceny
- **Krok** to 0,1–1% ceny (999$ → 1$, 1 500$ → 10$, 25 000$ → 100$).
- **Sprzedaż (szybko)** = najtańsza oferta − krok, ale **nigdy mniej niż**
  `koszt × (1 + min. zysk) / (1 − 3%)` → nie sprzedajesz ze stratą.
- **Skup** = `(najtańsza oferta − krok) × (1 − 3%) − marża`.
  Marża: `auto` (5–35%, rośnie przy dużych wahaniach i małej liczbie ofert), procent (`10%`) albo kwota za sztukę (`250`).

Przykład: najtańsza oferta 1 000$, marża 10% → sprzedaż 990$, skup do `990 × 0,97 − 99 = 861$`.

### Bezpieczniki automatycznych zmian
- podwyżka max **5%** na korektę i dopiero gdy wyższa cena utrzyma się **10 min**,
- obniżka max **25%** na korektę, a **od razu** gdy obecna cena skupu przestaje się opłacać,
- zmiany < 2% są pomijane, cena nigdy nie spada do 0 (w BK 0 = „kupuj za każdą cenę”),
- przy braku świeżych danych albo braku ofert ceny się nie zmieniają,
- każdy przedmiot ma własny przełącznik **Auto** i własną marżę.

Ceny sprzedaży w BK zmieniają się automatycznie tylko po włączeniu *Auto ceny sprzedaży* (domyślnie wyłączone) — sugestia jest zawsze widoczna w oknie.

## Dane
`config/bkanalityk/settings.json` – ustawienia, `config/bkanalityk/data.json` – historia (stany rynku, nowe i kupione oferty, Twoje zakupy, zmiany cen, lista wiszących ofert).
