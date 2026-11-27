package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Mejora 20: real-world CSV, repeated ids, points outside the plan and plan identity. */
class PointTemplateCsvTest {
    private fun parse(text: String, plan: PointTemplateParser.PlanIdentity = PointTemplateParser.PlanIdentity()) =
        PointTemplateParser.parse(text, 1000, 800, "floor-1", "b", plan = plan)

    @Test
    fun quotedSeparatorsEscapesAndBomAreRead() {
        val csv = "﻿id,label,x,y\r\n" +
            "A1,\"Dock 1, north\",10,20\r\n" +
            "A2,\"Rack \"\"B\"\"\",30,40\n" +
            "A3,Aisle 3\\, east,50,60\n" +
            "A4,\"Two\nlines\",70,80\n"
        val preview = parse(csv)
        assertEquals(listOf("Dock 1, north", "Rack \"B\"", "Aisle 3, east", "Two\nlines"), preview.points.map { it.label })
        assertEquals(listOf(10f, 30f, 50f, 70f), preview.points.map { it.x })
        // Semicolon files with commas inside a quoted label.
        val semicolons = parse("id;label;x;y\nB1;\"Zona 1, muelle\";5;6\n")
        assertEquals("Zona 1, muelle", semicolons.points.single().label)
        try { parse("id,label,x,y\nA1,\"open,1,2\n"); fail() } catch (_: ConfigException) {}
    }

    @Test
    fun repeatedIdsAndOutsidePointsAreShownNotSilentlyDropped() {
        val preview = parse("id,label,x,y\nA1,First,10,10\nA1,Second,20,20\nA2,Far,5000,10\nA3,Ok,30,30\n")
        assertEquals(listOf("A1"), preview.repeatedIds)
        assertEquals(listOf("First", "Ok"), preview.points.map { it.label })
        assertEquals(listOf("Far"), preview.outsideLabels)
        assertEquals(1, preview.outside)
    }

    @Test
    fun aDifferentPlanWithTheSameProportionsIsRefusedWhenItsIdentityIsKnown() {
        val plan = PointTemplateParser.PlanIdentity("11111111-1111-4111-8111-111111111111", "ab".repeat(32))
        // Same 5:4 proportions, but made for another plan image / floor plan.
        try { parse("# image 2000x1600 sha256=${"cd".repeat(32)}\nlabel,x,y\nA,1,1\n", plan); fail() } catch (e: ConfigException) {
            assertTrue("SHA-256" in e.message!!)
        }
        val json = """{"format":"wiprober-points","version":1,"image":{"width":2000,"height":1600,"floorPlanId":"22222222-2222-4222-8222-222222222222"},"points":[{"x":1,"y":1}]}"""
        try { parse(json, plan); fail() } catch (_: ConfigException) {}

        val matched = parse("# image 2000x1600 floorPlanId=11111111-1111-4111-8111-111111111111\nlabel,x,y\nA,1,1\n", plan)
        assertEquals(PointTemplateParser.PlanMatch.MATCHED, matched.planMatch)
        assertFalse(matched.needsConfirmation)
        assertEquals(PointTemplateParser.PlanMatch.MATCHED, parse("# image 2000x1600 sha256=${"AB".repeat(32)}\nlabel,x,y\nA,1,1\n", plan).planMatch)
        // Without metadata only proportions can be checked: the preview asks for confirmation.
        val unverified = parse("# image 2000x1600\nlabel,x,y\nA,1,1\n", plan)
        assertEquals(PointTemplateParser.PlanMatch.UNVERIFIED, unverified.planMatch)
        assertTrue(unverified.needsConfirmation)
    }
}
