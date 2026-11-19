package vn.nghenho.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.nghenho.App
import vn.nghenho.data.ConvStatus
import vn.nghenho.data.Conversation
import vn.nghenho.data.Segment
import vn.nghenho.util.DateRange
import vn.nghenho.util.Keywords

/** "Hỏi trí nhớ": tìm các cuộc hội thoại liên quan trong máy rồi nhờ AI trả lời. */
object Memory {
    suspend fun answer(question: String): String = withContext(Dispatchers.IO) {
        val dao = App.dao
        val now = System.currentTimeMillis()
        val range = DateRange.detect(question, now)
        val picked = LinkedHashMap<Long, Conversation>()
        val score = HashMap<Long, Int>()
        val segHits = HashMap<Long, MutableList<Segment>>()

        if (range != null) {
            dao.convsBetween(range.first, range.second)
                .filter { it.status == ConvStatus.DONE && !it.trivial }
                .takeLast(40)
                .forEach { picked[it.id] = it; score[it.id] = 1 }
        }
        for (k in Keywords.extract(question)) {
            val like = "%$k%"
            dao.searchConvs(like, 20).forEach {
                picked.putIfAbsent(it.id, it)
                score[it.id] = (score[it.id] ?: 0) + 3
            }
            dao.searchSegments(like, 40).forEach { s ->
                score[s.conversationId] = (score[s.conversationId] ?: 0) + 1
                segHits.getOrPut(s.conversationId) { mutableListOf() }.add(s)
            }
        }
        val missing = score.keys.filter { it !in picked }
        if (missing.isNotEmpty()) dao.convsByIds(missing).forEach { picked[it.id] = it }
        if (picked.isEmpty()) dao.recentDone(10).forEach { picked[it.id] = it }

        val ranked = picked.values
            .filter { c -> range == null || c.startedAt in range.first until range.second || (score[c.id] ?: 0) > 1 }
            .sortedWith(compareByDescending<Conversation> { score[it.id] ?: 0 }.thenByDescending { it.startedAt })
            .take(if (range != null) 40 else 12)
            .sortedBy { it.startedAt }

        val memory = buildString {
            for (c in ranked) append(Prompts.memoryBlock(c, dao.actionsOf(c.id), segHits[c.id].orEmpty()))
        }.take(150_000)
        val history = dao.lastChat(7).drop(1).reversed()
        val pendingN = dao.pendingCount()
        val note = if (pendingN > 0) "LƯU Ý: còn $pendingN đoạn âm thanh chưa chép lời xong nên CHƯA có trong KÝ ỨC – nếu người dùng hỏi về điều vừa nói, hãy nói rõ là đang chờ chép lời." else ""
        val user = Prompts.chatUser(question, now, memory, dao.openActions(20), dao.upcomingEvents(now, 10), history, note)
        Llm.complete(Prompts.chatSystem(), user, null, 8_000).trim()
    }
}
