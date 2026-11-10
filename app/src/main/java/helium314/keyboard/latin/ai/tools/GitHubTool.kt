// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

import helium314.keyboard.latin.ai.AiToolRegistry
import helium314.keyboard.latin.ai.SecureApiKeys
import helium314.keyboard.latin.settings.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub REST API tool for Hermes Keyboard.
 *
 * Supports read and comment actions using an optional Personal Access Token
 * stored in EncryptedSharedPreferences (`Settings.PREF_GITHUB_API_KEY`):
 *  - `my_repos`: list recent repositories for the authenticated user or a named owner
 *  - `list_issues`: list open issues in `owner/repo`
 *  - `list_prs`: list open pull requests in `owner/repo`
 *  - `get_issue`: fetch details and recent comments for an issue or PR number
 *  - `create_issue`: open a new issue in `owner/repo`
 *  - `comment_issue`: post a comment on an issue or PR number in `owner/repo`
 */
class GitHubTool : AiTool {

    override val name: String = "github_api"

    override val description: String =
        "Query or interact with GitHub repositories, issues, and pull requests via the GitHub REST API. " +
            "Actions: 'my_repos' (list repos for authenticated user or owner), " +
            "'list_issues' (list open issues in owner/repo), " +
            "'list_prs' (list open pull requests in owner/repo), " +
            "'get_issue' (get issue/PR details by number in owner/repo), " +
            "'create_issue' (create an issue with title and body in owner/repo), " +
            "'comment_issue' (post a comment body on an issue/PR number in owner/repo)."

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put(
                    "description",
                    "Action to run: 'my_repos', 'list_issues', 'list_prs', 'get_issue', 'create_issue', or 'comment_issue'."
                )
            })
            put("repo", JSONObject().apply {
                put("type", "string")
                put("description", "Repository in 'owner/repo' format (for example 'Thor-DraperJr/HermesKeyboard') or owner name for 'my_repos'.")
            })
            put("number", JSONObject().apply {
                put("type", "integer")
                put("description", "Issue or pull request number (required for 'get_issue' and 'comment_issue').")
            })
            put("title", JSONObject().apply {
                put("type", "string")
                put("description", "Issue title (required for 'create_issue').")
            })
            put("body", JSONObject().apply {
                put("type", "string")
                put("description", "Issue body or comment text (used by 'create_issue' and 'comment_issue').")
            })
        })
        put("required", JSONArray().put("action"))
    }

    override val gate: ToolGate = ToolGate.NETWORK

    override fun execute(args: JSONObject, ctx: ToolContext): AiToolRegistry.ToolResult {
        val action = args.optString("action", "").trim().lowercase()
        val repo = args.optString("repo", "").trim().removePrefix("https://github.com/").trim('/')
        val number = args.optInt("number", -1)
        val title = args.optString("title", "").trim()
        val body = args.optString("body", "").trim()
        val token = SecureApiKeys.getKey(Settings.PREF_GITHUB_API_KEY)

        return try {
            when (action) {
                "my_repos" -> listRepos(repo, token)
                "list_issues" -> {
                    if (!repo.contains('/')) return AiToolRegistry.ToolResult("Error: 'repo' must be in 'owner/repo' format.", isError = true)
                    listIssues(repo, token)
                }
                "list_prs" -> {
                    if (!repo.contains('/')) return AiToolRegistry.ToolResult("Error: 'repo' must be in 'owner/repo' format.", isError = true)
                    listPulls(repo, token)
                }
                "get_issue" -> {
                    if (!repo.contains('/') || number <= 0) {
                        return AiToolRegistry.ToolResult("Error: 'repo' (owner/repo) and positive 'number' are required.", isError = true)
                    }
                    getIssue(repo, number, token)
                }
                "create_issue" -> {
                    if (token.isBlank()) return AiToolRegistry.ToolResult("Error: GitHub Personal Access Token is not configured in Hermes Keyboard AI settings.", isError = true)
                    if (!repo.contains('/') || title.isBlank()) {
                        return AiToolRegistry.ToolResult("Error: 'repo' (owner/repo) and 'title' are required.", isError = true)
                    }
                    createIssue(repo, title, body, token)
                }
                "comment_issue" -> {
                    if (token.isBlank()) return AiToolRegistry.ToolResult("Error: GitHub Personal Access Token is not configured in Hermes Keyboard AI settings.", isError = true)
                    if (!repo.contains('/') || number <= 0 || body.isBlank()) {
                        return AiToolRegistry.ToolResult("Error: 'repo' (owner/repo), 'number', and 'body' are required.", isError = true)
                    }
                    commentIssue(repo, number, body, token)
                }
                else -> AiToolRegistry.ToolResult(
                    "Unknown action '$action'. Use 'my_repos', 'list_issues', 'list_prs', 'get_issue', 'create_issue', or 'comment_issue'.",
                    isError = true
                )
            }
        } catch (e: Exception) {
            AiToolRegistry.ToolResult("GitHub API error: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private fun request(method: String, path: String, token: String, payload: JSONObject? = null): Pair<Int, String> {
        val url = URL("https://api.github.com$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "HermesKeyboard/1.3")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (payload != null) {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun listRepos(owner: String, token: String): AiToolRegistry.ToolResult {
        val path = when {
            owner.isNotBlank() && !owner.contains('/') ->
                "/users/${URLEncoder.encode(owner, "UTF-8")}/repos?sort=updated&per_page=10"
            token.isNotBlank() ->
                "/user/repos?sort=updated&per_page=10&affiliation=owner,collaborator"
            else ->
                "/users/Thor-DraperJr/repos?sort=updated&per_page=10"
        }
        val (code, body) = request("GET", path, token)
        if (code !in 200..299) return AiToolRegistry.ToolResult("GitHub HTTP $code: ${ body.take(300) }", isError = true)
        val arr = JSONArray(body)
        if (arr.length() == 0) return AiToolRegistry.ToolResult("No repositories found.")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val fullName = r.optString("full_name")
            val desc = r.optString("description", "").takeIf { it != "null" && it.isNotBlank() } ?: "No description"
            val priv = if (r.optBoolean("private")) "private" else "public"
            sb.append("${i + 1}. $fullName ($priv) - $desc\n")
        }
        return AiToolRegistry.ToolResult(sb.toString().trim())
    }

    private fun listIssues(repo: String, token: String): AiToolRegistry.ToolResult {
        val (code, body) = request("GET", "/repos/$repo/issues?state=open&per_page=10", token)
        if (code !in 200..299) return AiToolRegistry.ToolResult("GitHub HTTP $code: ${body.take(300)}", isError = true)
        val arr = JSONArray(body)
        val sb = StringBuilder()
        var count = 0
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.has("pull_request")) continue
            count++
            val num = item.optInt("number")
            val title = item.optString("title")
            val user = item.optJSONObject("user")?.optString("login") ?: "unknown"
            sb.append("#$num: $title (by $user)\n")
        }
        if (count == 0) return AiToolRegistry.ToolResult("No open issues in $repo.")
        return AiToolRegistry.ToolResult(sb.toString().trim())
    }

    private fun listPulls(repo: String, token: String): AiToolRegistry.ToolResult {
        val (code, body) = request("GET", "/repos/$repo/pulls?state=open&per_page=10", token)
        if (code !in 200..299) return AiToolRegistry.ToolResult("GitHub HTTP $code: ${body.take(300)}", isError = true)
        val arr = JSONArray(body)
        if (arr.length() == 0) return AiToolRegistry.ToolResult("No open pull requests in $repo.")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val pr = arr.getJSONObject(i)
            val num = pr.optInt("number")
            val title = pr.optString("title")
            val user = pr.optJSONObject("user")?.optString("login") ?: "unknown"
            val head = pr.optJSONObject("head")?.optString("ref") ?: ""
            sb.append("PR #$num: $title ($head by $user)\n")
        }
        return AiToolRegistry.ToolResult(sb.toString().trim())
    }

    private fun getIssue(repo: String, number: Int, token: String): AiToolRegistry.ToolResult {
        val (code, body) = request("GET", "/repos/$repo/issues/$number", token)
        if (code !in 200..299) return AiToolRegistry.ToolResult("GitHub HTTP $code: ${body.take(300)}", isError = true)
        val obj = JSONObject(body)
        val title = obj.optString("title")
        val state = obj.optString("state")
        val user = obj.optJSONObject("user")?.optString("login") ?: "unknown"
        val issueBody = obj.optString("body", "").take(1200)
        val htmlUrl = obj.optString("html_url")
        return AiToolRegistry.ToolResult(
            "#$number [$state] $title (by $user)\nURL: $htmlUrl\n\n$issueBody".trim()
        )
    }

    private fun createIssue(repo: String, title: String, body: String, token: String): AiToolRegistry.ToolResult {
        val payload = JSONObject().apply {
            put("title", title)
            if (body.isNotBlank()) put("body", body)
        }
        val (code, resp) = request("POST", "/repos/$repo/issues", token, payload)
        if (code !in 200..299) return AiToolRegistry.ToolResult("GitHub HTTP $code: ${resp.take(300)}", isError = true)
        val obj = JSONObject(resp)
        return AiToolRegistry.ToolResult("Created issue #${obj.optInt("number")}: ${obj.optString("html_url")}")
    }

    private fun commentIssue(repo: String, number: Int, body: String, token: String): AiToolRegistry.ToolResult {
        val payload = JSONObject().apply { put("body", body) }
        val (code, resp) = request("POST", "/repos/$repo/issues/$number/comments", token, payload)
        if (code !in 200..299) return AiToolRegistry.ToolResult("GitHub HTTP $code: ${resp.take(300)}", isError = true)
        val obj = JSONObject(resp)
        return AiToolRegistry.ToolResult("Posted comment on #$number: ${obj.optString("html_url")}")
    }
}
