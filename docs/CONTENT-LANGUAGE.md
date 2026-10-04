# Content language filter (Video and Music)

Next to the genre the media centre offers a second choice: the **language of the content**.

* **Video** (Films | Series grid): `[Films] [Recommended] [All genres] [All languages]` — on a phone in two rows;
  on a TV or a tablet four equally wide choices that always fit the screen.
* **Music**: `All languages ▾` at the start of the genre row.

The choice lasts while the app is open, like the genre.

## Where the language comes from

### Films and series

Catalogues do not say in which language a title is. The public catalogue has a production country, but no
language. Safeer asks **Wikidata** (a free knowledge base; no key, no account) for two facts about a title, by
its IMDb id: the languages of the work (P364) and its countries of origin (P495).

A title can list several languages. The filter uses the **main** one (`IzvirniJezik.glavni`):

1. a language that is at home in one of the countries of origin wins (an American film with some French and
   Japanese dialogue is English);
2. between several such languages English wins (an American film with a German co-producer is not a German film);
3. without English all of them stay (a Belgian film in French and Dutch is found under both);
4. Serbo-Croatian films of former Yugoslavia are found under Croatian, Serbian and Bosnian.

A title whose language is not known is **not shown** while a language is selected.

For a selected language Wikidata can also list the titles themselves, ordered by how widely they are known. That
is how the filter finds films the general catalogues do not have on their first pages. The list is filtered by
the same main-language rule. Cards show the title in the language of the interface when Wikidata has it, otherwise
in English. With a genre selected as well, only the catalogues are used. English uses the catalogues only.

Everything else stays as it is: a card opens the same details, episodes and streams as a catalogue card, and in
the strict grid a title appears only after an add-on has confirmed that it can be played.

### Music

Jamendo selects tracks by the language of the lyrics (`lang`) and reports it for every track. Items from other
music sources are shown only when the source states a matching language. The user's own playlists stay.

## What leaves the device

Only when a language is selected:

* to Wikidata: the public IMDb ids of the titles in the catalogue on screen and the selected language;
* to Jamendo: the selected language as a query parameter.

Nothing about the user, the installed add-ons or what is being watched. Requests identify the app as
`SafeerOS/<version> (https://safeer.si)`, as Wikidata asks.

## Limits and caching

* At most four requests to Wikidata at a time; a "too many requests" answer is respected (`Retry-After`).
* A known language is kept for 180 days, an unknown one for 7 days (`files/izvirni-jeziki.tsv`, at most 20 000
  titles). Lists per language are kept in memory for six hours.
* Measured on a phone (4 October 2026): 50 titles in about one second; the list of a language in 0.2–3 s; a
  grid for a newly selected language in about three seconds.

Rules and parsing are plain Kotlin without Android (`IzvirniJezik.kt`, tested in `tests/IzvirniJezikTest.kt`);
network and cache are in `IzvirniJeziki.kt`.
