package eu.kanade.tachiyomi.extension.zh.yymh

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Yymh : ParsedHttpSource(), ConfigurableSource {
    override val lang = "zh"
    override val supportsLatest = true
    override val name = "Yymh"
    override val baseUrl = "https://www.yymanhua.com"
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(CommentsInterceptor)
        .build()

    private val preferences: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    // Some mangas are blocked without this
    override fun headersBuilder() = super.headersBuilder()

    override fun popularMangaRequest(page: Int): Request {
        return if (page < 2) {
            GET("$baseUrl/manga-list/", headers)
        } else {
            GET("$baseUrl/manga-list-p$page/", headers)
        }
    }
    override fun popularMangaNextPageSelector(): String = "div.page-pagination a:contains(>)"
    override fun popularMangaSelector(): String = "ul.mh-list > li > div.mh-item"
    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("h2.title > a")!!.text()
        thumbnail_url = element.selectFirst("img.mh-cover")!!.attr("src")
        url = element.selectFirst("h2.title > a")!!.attr("href")
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return if (page < 2) {
            GET("$baseUrl/manga-list-0-0-2/", headers)
        } else {
            GET("$baseUrl/manga-list-0-0-2-p$page/", headers)
        }
    }
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/search?title=$query&language=1&page=$page", headers)
    }
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaSelector(): String = "ul.mh-list > li, div.banner_detail_form"
    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst(".title > a")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
            ?: element.selectFirst("p.mh-cover")!!.attr("style")
                .substringAfter("url(").substringBefore(")")
        url = element.selectFirst(".title > a")!!.attr("href")
    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("div.detail-info p.detail-info-title")!!.ownText()
        thumbnail_url = document.selectFirst("div.detail-info img.detail-info-cover")!!.attr("src")
        author = document.selectFirst("div.detail-info p.detail-info-tip  > span > a")!!.ownText()
        artist = author
        genre = document.select("div.detail-info p.detail-info-tip span > span.item").eachText().joinToString(", ")
        val el = document.selectFirst("div.detail-info p.detail-info-content")!!
        description = el.ownText() + el.selectFirst("span")?.ownText().orEmpty()
        status = when (document.selectFirst("div.detail-info p.detail-info-tip > span > span")!!.text()) {
            "連載中" -> SManga.ONGOING
            "已完結" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
            // May need to click button on website to read
            // document.selectFirst("ul#chapterlistload")?.attr("class")
            ?: throw Exception("請到webview確認")
        val li = document.select("div#chapterlistload a").map {
            SChapter.create().apply {
                url = it.attr("href")
                name = if (it.selectFirst("span.detail-lock, span.view-lock") != null) {
                    "\uD83D\uDD12"
                } else {
                    ""
                } + (it.selectFirst("p.title")?.text() ?: it.text())
            }
        }

        // Sort chapter by url (related to upload time)
        /*
        if (preferences.getBoolean(SORT_CHAPTER_PREF, false)) {
            return li.sortedByDescending { it.url.drop(2).dropLast(1).toInt() }
        }
         */

        // Sometimes list is in ascending order, probably unread paid manga
        return li
    }
    override fun chapterListSelector(): String = throw UnsupportedOperationException()
    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException()

    override fun pageListParse(document: Document): List<Page> {
        val images = document.select("div#barChapter > img.load-src")
        val result: ArrayList<Page>
        val script = document.selectFirst("script:containsData(YYMANHUA_MID)")!!.data()
        if (!script.contains("YYMANHUA_VIEWSIGN_DT")) {
            throw Exception(document.selectFirst("div.view-pay-form p.subtitle")!!.text())
        }
        val cid = script.substringAfter("var YYMANHUA_CID=").substringBefore(";")
        if (!images.isEmpty()) {
            result = images.mapIndexed { index, it ->
                Page(index, "", it.attr("data-src"))
            } as ArrayList<Page>
        } else {
            val mid = script.substringAfter("var YYMANHUA_MID=").substringBefore(";")
            val dt = script.substringAfter("var YYMANHUA_VIEWSIGN_DT=\"").substringBefore("\";")
            val sign = script.substringAfter("var YYMANHUA_VIEWSIGN=\"").substringBefore("\";")
            val requestUrl = document.location()
            val imageCount = script.substringAfter("var YYMANHUA_IMAGE_COUNT=").substringBefore(";").toInt()
            result = (1..imageCount).map {
                val url = requestUrl.toHttpUrl().newBuilder()
                    .addPathSegment("chapterimage.ashx")
                    .addQueryParameter("cid", cid)
                    .addQueryParameter("page", it.toString())
                    .addQueryParameter("key", "")
                    .addQueryParameter("_cid", cid)
                    .addQueryParameter("_mid", mid)
                    .addQueryParameter("_dt", dt)
                    .addQueryParameter("_sign", sign)
                    .build()
                Page(it, url.toString())
            } as ArrayList<Page>
        }

        return result
    }

    override fun imageUrlRequest(page: Page): Request {
        val referer = page.url.substringBefore("chapterimage.ashx")
        val header = headers.newBuilder().add("Referer", referer).build()
        return GET(page.url, header)
    }

    override fun imageUrlParse(response: Response): String {
        val script = Unpacker.unpack(response.body.string())
        val pix = script.substringAfter("var pix=\"").substringBefore("\"")
        val pvalue = script.substringAfter("var pvalue=[\"").substringBefore("\"")
        val query = script.substringAfter("pix+pvalue[i]+\"").substringBefore("\"")
        return pix + pvalue + query
    }
    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val header = headers.newBuilder().add("Referer", baseUrl).build()
        return GET(page.imageUrl!!, header)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        return
    }
}
