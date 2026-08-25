package eu.kanade.tachiyomi.extension.pt.superhentais

import eu.kanade.tachiyomi.source.model.Filter

abstract class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val value: String
        get() = options[state].second
}

class ContentFilter : SelectFilter("Tipo de conteúdo", CONTENT_OPTIONS)

class LetterFilter : SelectFilter("Letra inicial", LETTER_OPTIONS)

class StatusFilter : SelectFilter("Status", STATUS_OPTIONS)

class CensureFilter : SelectFilter("Censura", CENSURE_OPTIONS)

class SortFilter : SelectFilter("Ordem", SORT_OPTIONS)

class ExclusiveModeFilter : Filter.CheckBox("Exigir todos os tags incluídos", true)

class Tag(val id: String, name: String) : Filter.TriState(name)

class GenreFilter : Filter.Group<Tag>("Tags", GENRES.map { Tag(it.second, it.first) })

private val CONTENT_OPTIONS = listOf(
    "Hentai Manga" to "5",
    "Cartoon Ero" to "1",
    "HQ Ero" to "6",
    "Manhwa" to "10",
)

private val LETTER_OPTIONS = listOf(
    "Todas" to "0",
    "#09" to "1",
) + ('A'..'Z').map { it.toString() to it.toString() }

private val STATUS_OPTIONS = listOf(
    "Sem filtro" to "0",
    "Completo" to "complete",
    "Em progresso" to "progress",
    "Incompleto" to "incomplete",
)

private val CENSURE_OPTIONS = listOf(
    "Sem filtro" to "0",
    "Sem censura" to "yes",
    "Com censura" to "no",
)

private val SORT_OPTIONS = listOf(
    "Mais acessados" to "more_access",
    "Menos acessados" to "less_access",
    "Mais novos" to "date-desc",
    "Mais antigos" to "date-asc",
    "Mais curtidos" to "more_like",
    "Menos curtidos" to "less_like",
    "Mais capítulos" to "post-more",
    "Menos capítulos" to "post-less",
    "Nome (A-Z)" to "a-z",
    "Nome (Z-A)" to "z-a",
)

private val GENRES = listOf(
    "Ação" to "75",
    "Ahegao" to "16",
    "Artes Marciais" to "76",
    "Aventura" to "77",
    "BDSM" to "45",
    "Colegial" to "17",
    "Comédia" to "53",
    "Cross-Dressing" to "64",
    "Drama" to "80",
    "Eroge" to "81",
    "Esporte" to "82",
    "Fantasia" to "46",
    "Femdom" to "38",
    "Ficção Científica" to "83",
    "Furry" to "59",
    "Futanari" to "25",
    "Gender Bender" to "63",
    "Gerakuro" to "129",
    "Gore" to "86",
    "Grupo" to "13",
    "Gyaru" to "71",
    "Harém" to "24",
    "Histórico" to "87",
    "Josei" to "89",
    "Kemono" to "91",
    "Kemonomimi" to "90",
    "Lolicon" to "22",
    "Magia" to "92",
    "Mistério" to "94",
    "Polícial" to "57",
    "Psicológico" to "97",
    "Raio-X" to "19",
    "Romance" to "27",
    "Scat" to "116",
    "Seinen" to "98",
    "Shotacon" to "33",
    "Slice of Life" to "99",
    "Smegma" to "49",
    "Sobrenatural" to "100",
    "Superpoder" to "101",
    "Tentáculos" to "56",
    "Terror" to "102",
    "Thriller" to "103",
    "Tortura" to "126",
    "Twintails" to "108",
    "Vanilla" to "105",
    "Voyeur" to "106",
    "Yandere" to "127",
    "Zoofilia" to "54",
    "Adesivos" to "74",
    "Asiática" to "130",
    "Avental" to "128",
    "Bestialidade" to "72",
    "Brinquedos Sexuais" to "14",
    "Bukkake" to "58",
    "Bunda Grande" to "48",
    "Chantagem" to "36",
    "Cheongsam" to "119",
    "Chuva Dourada" to "85",
    "Cosplay" to "78",
    "Dark Skin" to "21",
    "Demônio" to "79",
    "Dormindo" to "37",
    "Drogas" to "15",
    "Dupla Penetração" to "41",
    "Elfa" to "7",
    "Empregada" to "11",
    "Enfermeira" to "51",
    "Escravidão" to "35",
    "Espanhola" to "43",
    "Estupro" to "18",
    "Exibicionismo" to "28",
    "Facial" to "23",
    "Filmando" to "112",
    "Freira" to "113",
    "Futa on Futa" to "131",
    "Futa on Male" to "40",
    "Garota Monstro" to "39",
    "Gay" to "52",
    "Glory Hole" to "114",
    "Gokkun" to "84",
    "Gordinha" to "70",
    "Gozando Dentro" to "3",
    "Grávida" to "44",
    "Hipnose" to "60",
    "Impregnação" to "118",
    "Incesto" to "9",
    "Jogos Eróticos" to "88",
    "Kawaii" to "95",
    "Kimono" to "122",
    "Lactação" to "42",
    "Látex" to "124",
    "Lésbicas" to "31",
    "Loira(o)" to "69",
    "Luvas" to "65",
    "Mamilos Invertidos" to "132",
    "Máscara" to "68",
    "Masturbação" to "47",
    "Mecha" to "93",
    "Meia Calça" to "120",
    "Meias" to "66",
    "Milf" to "30",
    "Monstros" to "62",
    "Óculos" to "96",
    "Óleo" to "125",
    "Palmadas" to "123",
    "Peitos Grandes" to "1",
    "Peitos Pequenos" to "10",
    "Pelos Pubianos" to "73",
    "Pênis Grande" to "5",
    "Pênis Pequeno" to "6",
    "Piercings" to "107",
    "Preservativo" to "32",
    "Professor" to "50",
    "Professora" to "20",
    "Prostituição" to "29",
    "Quebra da Mente" to "34",
    "Rabo De Cavalo" to "121",
    "Roupa De Banho" to "26",
    "Roupa Íntima" to "67",
    "Ruiva(o)" to "109",
    "Sexo Anal" to "4",
    "Sexo Com Pés" to "55",
    "Sexo oral" to "2",
    "Sexo Público" to "61",
    "Tatuagens" to "111",
    "Traição" to "12",
    "Travesti" to "117",
    "Uniforme Escolar" to "115",
    "Vampiros" to "104",
    "Vida Escolar" to "110",
    "Virgindade" to "8",
)
