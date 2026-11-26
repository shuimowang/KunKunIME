package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolActionTest {
    @Test fun asciiGoesThroughEngine() {
        assertEquals(KeyAction.Text('!'), symbolActionFor('!', true))
        assertEquals(KeyAction.Text('&'), symbolActionFor('&', false))
    }

    @Test fun nonAsciiRightQuoteCommitsDirectlyWhenChinesePunct() {
        assertEquals(KeyAction.Commit("”"), symbolActionFor('”', true))
    }

    @Test fun fullWidthQuoteFallsBackToAsciiWhenChinesePunctOff() {
        assertEquals(KeyAction.Text('"'), symbolActionFor('”', false))
        assertEquals(KeyAction.Text('"'), symbolActionFor('“', false))
    }

    @Test fun otherNonAsciiCommitsDirectly() {
        assertEquals(KeyAction.Commit("、"), symbolActionFor('、', false))
    }
}
