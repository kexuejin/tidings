// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopPatchMergeTest {

    @Test
    fun formatCompareUrlParsesGitHubSshAndHttpsUrls() {
        val sshUrl = "git@github.com:kapp/tenon.git"
        val sshCompare = DesktopPatchMerge.formatCompareUrl(sshUrl, "feature/demo")
        assertNotNull(sshCompare)
        assertEquals("https://github.com/kapp/tenon/compare/feature/demo?expand=1", sshCompare)

        val httpsUrl = "https://github.com/kapp/tenon.git"
        val httpsCompare = DesktopPatchMerge.formatCompareUrl(httpsUrl, "task-123")
        assertNotNull(httpsCompare)
        assertEquals("https://github.com/kapp/tenon/compare/task-123?expand=1", httpsCompare)
    }

    @Test
    fun formatCompareUrlParsesGitLabSshAndHttpsUrls() {
        val sshUrl = "git@gitlab.com:kapp/tenon-repo.git"
        val sshCompare = DesktopPatchMerge.formatCompareUrl(sshUrl, "patch-42")
        assertNotNull(sshCompare)
        assertEquals("https://gitlab.com/kapp/tenon-repo/-/merge_requests/new?merge_request%5Bsource_branch%5D=patch-42", sshCompare)

        val httpsUrl = "https://gitlab.com/kapp/tenon-repo"
        val httpsCompare = DesktopPatchMerge.formatCompareUrl(httpsUrl, "patch-42")
        assertNotNull(httpsCompare)
        assertEquals("https://gitlab.com/kapp/tenon-repo/-/merge_requests/new?merge_request%5Bsource_branch%5D=patch-42", httpsCompare)
    }

    @Test
    fun formatCompareUrlReturnsNullForUnknownRemotes() {
        assertNull(DesktopPatchMerge.formatCompareUrl("file:///tmp/repo", "main"))
        assertNull(DesktopPatchMerge.formatCompareUrl("custom-git-server.internal:repo.git", "main"))
    }
}
