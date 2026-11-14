package dev.picasso.recovery.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PicassoAnswersTest {
    @Test fun `an APPROVED answer carries the instance, the execution, every step's unit and what went out`() {
        val j = judgePicassoAnswer(PicassoAnswers.approved(units = listOf("remedy-1-pick_place", "remedy-2-pick_place")))
        assertIs<DispatchJudgement.Accepted>(j)
        assertEquals("mw-1", j.instanceId)
        assertEquals("exec-42", j.executionId)
        assertEquals(listOf("remedy-1-pick_place", "remedy-2-pick_place"), j.units)
        assertEquals("pick_place", j.delivered[0].get("skillType").textValue())
    }

    @Test fun `every refusal of schemas 4 and 5 is understood - three may have landed, the rest are refusals`() {
        assertEquals(setOf("4", "5"), PICASSO_APPROVAL_SCHEMAS)
        assertEquals(17, PICASSO_REFUSALS.size)
        for (schema in PICASSO_APPROVAL_SCHEMAS) for (r in PICASSO_REFUSALS) {
            // real picasso always sends the consumption record with CONSUMED
            val j = judgePicassoAnswer(
                if (r == "CONSUMED") PicassoAnswers.refused(r, schema, PicassoAnswers.consumedRecord(target = if (schema == "5") "null" else null))
                else PicassoAnswers.refused(r, schema),
            )
            val what = "$r in schema $schema"
            when {
                // schema 4 has no such value: not understood, to a person
                r == "HUMAN_APPROVAL_REQUIRED" && schema == "4" -> assertIs<DispatchJudgement.AnswerUnknown>(j, what)
                r == "CONSUMED" -> assertEquals(DispatchJudgement.MayHaveLanded(r, consumedExecutionId = "exec-7"), j, what)
                r == "NO_PROPOSAL" || r == "REMEDY_NOT_APPLIED" -> assertEquals(DispatchJudgement.MayHaveLanded(r), j, what)
                else -> assertEquals(DispatchJudgement.Refused(r), j, what)
            }
        }
    }

    @Test fun `HUMAN_APPROVAL_REQUIRED is a refusal for a person, never a may-have-landed or a retry`() {
        assertEquals(DispatchJudgement.Refused("HUMAN_APPROVAL_REQUIRED"), judgePicassoAnswer(PicassoAnswers.refused("HUMAN_APPROVAL_REQUIRED", schema = "5")))
    }

    @Test fun `a schema 5 CONSUMED of a hold recovery keeps the execution - the target stays in the answer only`() {
        val j = judgePicassoAnswer(PicassoAnswers.refused("CONSUMED", "5", PicassoAnswers.consumedRecord("exec-3", PicassoAnswers.HOLD_TARGET)))
        assertEquals(DispatchJudgement.MayHaveLanded("CONSUMED", consumedExecutionId = "exec-3"), j)
    }

    @Test fun `CONSUMED keeps the execution its consumption record names`() {
        val j = judgePicassoAnswer(PicassoAnswers.refused("CONSUMED", consumed = PicassoAnswers.consumedRecord("exec-7")))
        assertEquals(DispatchJudgement.MayHaveLanded("CONSUMED", consumedExecutionId = "exec-7"), j)
    }

    @Test fun `a CONSUMED without its record is still may-have-landed - lenient, never a retry`() {
        assertEquals(DispatchJudgement.MayHaveLanded("CONSUMED"), judgePicassoAnswer(PicassoAnswers.refused("CONSUMED", consumed = "null")))
    }

    @Test fun `anything not understood is an unknown answer`() {
        val step = """{"unitId":"remedy-1-pick_place","skillType":"pick_place","parameters":{}}"""
        listOf(
            PicassoAnswers.refused("SOMETHING_NEW"),
            PicassoAnswers.refused("REFUSED_BY_GATE", schema = "2"),
            PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3"),
            PicassoAnswers.refused("REFUSED_BY_GATE", schema = "6"),
            PicassoAnswers.refused("HUMAN_APPROVAL_REQUIRED", schema = "4"),
            PicassoAnswers.refused("SOMETHING_NEW", schema = "5"),
            // schema 2's APPROVED, as incident-recovery read it before: no instanceId, no unitId
            """{"schemaVersion":"2","contractSemver":"1.0.0","outcome":"APPROVED","executionId":"e","steps":[{"skillType":"pick_place","parameters":{}}]}""",
            """{"schemaVersion":"4","outcome":"APPROVED","executionId":"e","steps":[$step]}""",
            """{"schemaVersion":"4","instanceId":" ","outcome":"APPROVED","executionId":"e","steps":[$step]}""",
            """{"schemaVersion":"4","instanceId":7,"outcome":"APPROVED","executionId":"e","steps":[$step]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","steps":[$step]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e"}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":[]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":[{"skillType":"pick_place","parameters":{}}]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":[{"unitId":"","skillType":"pick_place"}]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":[$step,$step]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":["remedy-1-pick_place"]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"MAYBE"}""",
            """{"schemaVersion":"4","outcome":"REFUSED","refusal":"NO_PROPOSAL","reason":"r","consumed":null}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"REFUSED","refusal":"NO_PROPOSAL","refusal":"REFUSED_BY_GATE"}""",
            "{",
            "[]",
        ).forEach { assertIs<DispatchJudgement.AnswerUnknown>(judgePicassoAnswer(it), it) }
    }

    @Test fun `an answer of the wrong kind for the candidate is unknown`() {
        assertIs<DispatchJudgement.AnswerUnknown>(judgeDispatch(CandidateKind.OPERATOR_DECISION, DispatchResult.Answer(PicassoAnswers.approved())))
        assertIs<DispatchJudgement.AnswerUnknown>(judgeDispatch(CandidateKind.APPROVE_REMEDY, DispatchResult.PersonTaskIssued))
        assertEquals(DispatchJudgement.PersonTask, judgeDispatch(CandidateKind.CHOOSE_SOURCE, DispatchResult.PersonTaskIssued))
        assertEquals(DispatchJudgement.Uncertain("timeout"), judgeDispatch(CandidateKind.APPROVE_REMEDY, DispatchResult.Uncertain("timeout")))
    }
}
