package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.wind_android.SchemePreviewInfo
import java.io.File

class SchemeImportStateTest {

    private fun preview(
        conflicts: List<String> = emptyList(),
        overridesBuiltin: List<String> = emptyList(),
        overridesFiles: List<String> = emptyList(),
        replace: Boolean = false,
    ) = SchemeImportState.Preview(
        file = File("x"),
        info = SchemePreviewInfo(
            title = "", description = "", schemaIds = listOf("pinyin"),
            willAdd = emptyList(), conflicts = conflicts, systemRefs = emptyList(), missing = emptyList(),
            overridesBuiltin = overridesBuiltin, overridesBuiltinFiles = overridesFiles,
            ignoredConfigPatch = false,
        ),
        replace = replace,
    )

    @Test
    fun plainImportNeedsNoConfirm() {
        assertFalse(preview().needsConfirm)
    }

    @Test
    fun overridingBuiltinSchemaNeedsConfirm() {
        val p = preview(overridesBuiltin = listOf("pinyin"))
        assertEquals(listOf("pinyin"), p.effectiveOverrideSchemas)
        assertTrue(p.needsConfirm)
    }

    @Test
    fun shadowingBuiltinResourceNeedsConfirm() {
        assertTrue(preview(overridesFiles = listOf("pinyin/rime_frost.dict.yaml")).needsConfirm)
    }

    /** 用户层已有同名文件且这次 Merge 会跳过：不会真的遮住自带，不提示也不二次确认 */
    @Test
    fun skippedConflictsDoNotCountAsOverride() {
        val p = preview(
            conflicts = listOf("pinyin.schema.toml", "pinyin/a.dict.yaml"),
            overridesBuiltin = listOf("pinyin"),
            overridesFiles = listOf("pinyin/a.dict.yaml"),
        )
        assertTrue(p.effectiveOverrideSchemas.isEmpty())
        assertTrue(p.effectiveOverrideFiles.isEmpty())
        assertFalse(p.needsConfirm)
        // 打开「覆盖」后才算
        val r = p.copy(replace = true)
        assertEquals(listOf("pinyin"), r.effectiveOverrideSchemas)
        assertTrue(r.needsConfirm)
    }

    @Test
    fun replacingUserFilesNeedsConfirm() {
        assertTrue(preview(conflicts = listOf("my.schema.toml"), replace = true).needsConfirm)
        assertFalse(preview(conflicts = listOf("my.schema.toml"), replace = false).needsConfirm)
    }
}
