package helium314.keyboard.latin.ai

import helium314.keyboard.latin.ai.tools.AiToolCatalog
import helium314.keyboard.latin.ai.tools.GitHubTool
import helium314.keyboard.latin.ai.tools.ToolContext
import helium314.keyboard.latin.ai.tools.ToolGate
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HermesKeyboardCustomizationsTest {

    @Test
    fun `GitHubTool is registered in AiToolCatalog and gated on network`() {
        val tool = AiToolCatalog.byName("github_api")
        assertNotNull(tool, "github_api tool must be registered in AiToolCatalog")
        assertEquals(ToolGate.NETWORK, tool.gate)
        val enabledWithNetwork = AiToolCatalog.enabled(allowNetwork = true, allowActions = false)
        assertTrue(enabledWithNetwork.any { it.name == "github_api" })
        val disabledWithoutNetwork = AiToolCatalog.enabled(allowNetwork = false, allowActions = true)
        assertFalse(disabledWithoutNetwork.any { it.name == "github_api" })
    }

    @Test
    fun `GitHubTool validates required arguments cleanly without throwing`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("test_prefs", 0)
        val tool = GitHubTool()
        val ctx = ToolContext(context, null, prefs)

        val missingRepo = tool.execute(JSONObject().put("action", "list_issues"), ctx)
        assertTrue(missingRepo.isError)
        assertTrue(missingRepo.content.contains("owner/repo"))

        val unknownAction = tool.execute(JSONObject().put("action", "invalid_op"), ctx)
        assertTrue(unknownAction.isError)
        assertTrue(unknownAction.content.contains("Unknown action"))
    }

    @Test
    fun `Defaults encode Thor ASD-STE100 communication rules and reply alias`() {
        assertTrue(Defaults.PREF_AI_ALLOW_NETWORK_TOOLS)
        assertTrue(Defaults.PREF_AI_INSTRUCTION.contains("ASD-STE100"))
        assertTrue(Defaults.PREF_AI_LOREBOOK.contains("Thor Draper Jr."))

        val aliases = JSONArray(Defaults.PREF_AI_PROMPT_ALIASES)
        val names = (0 until aliases.length()).map { aliases.getJSONObject(it).getString("name") }
        assertTrue("reply" in names)
        assertTrue("ste" in names)

        val chips = JSONArray(Defaults.PREF_AI_TONE_CHIPS)
        val chipNames = (0 until chips.length()).map { chips.getJSONObject(it).getString("name") }
        assertTrue("Thor STE" in chipNames)
        assertTrue("Direct Reply" in chipNames)
        assertEquals("", SecureApiKeys.getKey(Settings.PREF_GITHUB_API_KEY))
        assertEquals("", SecureApiKeys.getKey(Settings.PREF_COPILOT_API_KEY))
        assertEquals("copilot:gemini-3.8-flash", Defaults.PREF_AI_MODEL)
        assertTrue(AiServiceSync.CLOUD_MODELS.any { it.second == "copilot:gemini-3.8-flash" })
        assertTrue(AiServiceSync.CLOUD_MODELS.any { it.second == "copilot:gemini-4-argon" })
    }
}
