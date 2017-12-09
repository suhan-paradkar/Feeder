package com.nononsenseapps.feeder.model

import android.util.Log
import com.nononsenseapps.feeder.util.asFeed
import com.nononsenseapps.feeder.util.relativeLinkIntoAbsolute
import com.nononsenseapps.feeder.util.relativeLinkIntoAbsoluteOrThrow
import com.nononsenseapps.feeder.util.sloppyLinkToStrictURL
import com.nononsenseapps.jsonfeed.Feed
import com.nononsenseapps.jsonfeed.JsonFeedParser
import com.nononsenseapps.jsonfeed.cachingHttpClient
import com.nononsenseapps.jsonfeed.feedAdapter
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL

object FeedParser {

    // Should reuse same instance to have same cache
    @Volatile private var client: OkHttpClient = cachingHttpClient()
    @Volatile private var jsonFeedParser: JsonFeedParser = JsonFeedParser(client, feedAdapter())

    /**
     * To enable caching, you need to call this method explicitly with a suitable cache directory.
     */
    @Synchronized fun setup(cacheDir: File?): FeedParser {
        this.client = cachingHttpClient(cacheDir)
        jsonFeedParser = JsonFeedParser(client, feedAdapter())

        return this
    }

    /**
     * Finds the preferred alternate link in the header of an HTML/XML document pointing to feeds.
     */
    fun findFeedUrl(html: String,
                    preferRss: Boolean = false,
                    preferAtom: Boolean = false,
                    preferJSON: Boolean = false): URL? {

        val feedLinks = getAlternateFeedLinksInHtml(html)
                .sortedBy {
                    val t = it.second.toLowerCase()
                    when {
                        preferAtom && t.contains("atom") -> "0"
                        preferRss && t.contains("rss") -> "1"
                        preferJSON && t.contains("json") -> "2"
                        else -> t
                    }
                }
                .map {
                    sloppyLinkToStrictURL(it.first) to it.second
                }

        return feedLinks.firstOrNull()?.first
    }

    /**
     * Returns all alternate links in the header of an HTML/XML document pointing to feeds.
     */
    fun getAlternateFeedLinksAtUrl(url: URL): List<Pair<String, String>> {
        val html = curl(url)
        return when {
            html != null -> getAlternateFeedLinksInHtml(html, baseUrl = url)
            else -> emptyList()
        }
    }

    /**
     * Returns all alternate links in the header of an HTML/XML document pointing to feeds.
     */
    fun getAlternateFeedLinksInHtml(html: String, baseUrl: URL? = null): List<Pair<String, String>> {
        val doc = Jsoup.parse(html.byteInputStream(), "UTF-8", "")
        val header = doc.head()

        return header.getElementsByAttributeValue("rel", "alternate")
                .filter { it.hasAttr("href") && it.hasAttr("type") }
                .filter {
                    val t = it.attr("type").toLowerCase()
                    when {
                        t.contains("application/atom") -> true
                        t.contains("application/rss") -> true
                        t.contains("application/json") -> true
                        else -> false
                    }
                }
                .filter {
                    val l = it.attr("href").toLowerCase()
                    try {
                        if (baseUrl != null) {
                            relativeLinkIntoAbsoluteOrThrow(base = baseUrl, link = l)
                        } else {
                            URL(l)
                        }
                        true
                    } catch (_: MalformedURLException) {
                        false
                    }
                }
                .map {
                    when {
                        baseUrl != null -> relativeLinkIntoAbsolute(base = baseUrl, link = it.attr("href")) to it.attr("type")
                        else -> sloppyLinkToStrictURL(it.attr("href")).toString() to it.attr("type")
                    }
                }
    }

    fun curl(url: URL): String? {
        var result: String? = null
        curlAndOnResponse(url) {
            result = it.body()?.string()
        }
        return result
    }

    private fun curlAndOnResponse(url: URL, block: ((Response) -> Unit)) {
        val response = getResponse(url)

        if (!response.isSuccessful) {
            throw IOException("Unexpected code " + response)
        }

        response.use {
            block(it)
        }
    }

    fun getResponse(url: URL): Response {
        val request = Request.Builder()
                .url(url)
                .build()

        val call = client.newCall(request)
        return call.execute()
    }

    @Throws(FeedParser.FeedParsingError::class)
    fun parseFeedUrl(url: URL): Feed {
        Log.d("RxFeedParser", "parseFeed: $url")
        try {

            var result: Feed? = null
            curlAndOnResponse(url) {
                result = parseFeedResponse(it)
            }

            return result!!
        } catch (e: Throwable) {
            throw FeedParsingError(e)
        }

    }

    @Throws(FeedParser.FeedParsingError::class)
    fun parseFeedResponse(response: Response): Feed {
        Log.d("RxFeedParser", "parseFeedResponse: ${response.request().url()}")
        try {

            var result: Feed? = null
            response.use {
                Log.d("RxRSSLOCAL", "cache response: " + it.cacheResponse())
                Log.d("RxRSSLOCAL", "network response: " + it.networkResponse())

                val isJSON = (it.header("content-type") ?: "").contains("json")
                // Pass straight bytes from response to parser to properly handle encoding
                val body = it.body()?.bytes()

                if (body != null) {
                    // Encoding is not an issue for reading HTML (probably)
                    val alternateFeedLink = findFeedUrl(String(body), preferAtom = true)

                    val feed = if (alternateFeedLink != null) {
                        parseFeedUrl(alternateFeedLink)
                    } else {
                        when (isJSON) {
                            true -> jsonFeedParser.parseJsonBytes(body)
                            false -> parseRssAtomBytes(body)
                        }
                    }

                    result = if (feed.feed_url == null) {
                        // Nice to return non-null value here
                        feed.copy(feed_url = it.request().url().toString())
                    } else {
                        feed
                    }
                } else {
                    throw NullPointerException("Response body was null")
                }
            }

            return result!!
        } catch (e: Throwable) {
            throw FeedParsingError(e)
        }

    }

    @Throws(FeedParser.FeedParsingError::class)
    private fun parseRssAtomBytes(feedXml: ByteArray): Feed {
        feedXml.inputStream().use { return parseFeedInputStream(it) }
    }

    @Throws(FeedParser.FeedParsingError::class)
    fun parseFeedInputStream(`is`: InputStream): Feed {
        `is`.use {
            try {
                val feed = SyndFeedInput().build(XmlReader(`is`))
                return feed.asFeed()
            } catch (e: Throwable) {
                throw FeedParsingError(e)
            }
        }
    }

    class FeedParsingError(e: Throwable) : Exception(e)
}
