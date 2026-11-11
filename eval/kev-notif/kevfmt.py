"""카톡·알림 레코드를 Kev 학습/평가 형식({"state", "questions"})으로 바꾸는 공통 규칙.

앱(MaeumPet)도 같은 state 형식과 같은 지시문으로 Kev를 불러야 학습한 대로 동작한다.
형식이나 지시문을 바꾸면 앱의 KevClassifier.TASKS와 state 조립 코드도 함께 바꿀 것.
"""

CHAT_CONTEXT_MAX = 4

TASKS = {
    # 카톡: 앞 대화를 보고 마지막 메시지가 "나"에게 하는 질문·요청인지
    "chat_question": "The last Korean chat message was sent to the phone owner (나). Does it ask 나 a genuine question that needs an answer? "
                     "Rhetorical questions, self-talk about the sender's own matters, unfinished story fragments, and questions aimed at someone else in a group chat do not count.",
    "chat_request": "The last Korean chat message was sent to the phone owner (나). Does it ask 나 to do something? "
                    "Joking or exclamatory commands, story fragments, the sender's own promises, and requests aimed at someone else in a group chat do not count. "
                    "A request split across several consecutive messages counts.",
    # 알림: 광고·참여 유도인지, 오늘 안에 봐야 하는지
    "notif_promo": "Is this Korean phone notification mainly trying to pull the user into the app for the sender's benefit, "
                   "such as ads, discounts, coupons, events, points to claim, recommendations, or engagement nudges?",
    "notif_timely": "Does the user need to see or act on this Korean phone notification today? "
                    "Fake urgency in ads does not count.",
}

YES_NO = {"yes": "Yes", "no": "No"}


def chat_state(room, context, target):
    """room: "1:1"|"group", context: [{"who","text"}...], target: {"who","text"}"""
    lines = [f"[대화방] {'단톡' if room == 'group' else '1:1'}"]
    ctx = context[-CHAT_CONTEXT_MAX:]
    if ctx:
        lines.append("[앞 대화]")
        lines += [f"{c['who']}: {c['text']}" for c in ctx]
    lines.append("[마지막 메시지]")
    lines.append(f"{target['who']}: {target['text']}")
    return "\n".join(lines)


def notif_state(app, title, text):
    lines = [f"[앱] {app}"]
    if title:
        lines.append(f"[제목] {title}")
    lines.append(f"[내용] {text}")
    return "\n".join(lines)


def question(task, label, src):
    return {"type": "choice", "instructions": TASKS[task], "criteria": dict(YES_NO), "label": label, "src": src}


def chat_record(r, src):
    return {"state": chat_state(r["room"], r["context"], r["target"]),
            "questions": {"chat_question": question("chat_question", r["question"], src),
                          "chat_request": question("chat_request", r["request"], src)},
            "_meta": {"id": r["id"], "source": src}}


def notif_record(r, src):
    return {"state": notif_state(r["app"], r.get("title", ""), r["text"]),
            "questions": {"notif_promo": question("notif_promo", r["promo"], src),
                          "notif_timely": question("notif_timely", r["timely"], src)},
            "_meta": {"id": r["id"], "source": src}}
