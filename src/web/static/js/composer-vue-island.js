(() => {
  const bridge = window.__ZAOMENG_LEGACY_BRIDGE__;
  const vue = window.Vue;
  const host = document.getElementById("composer-vue-root");
  const stage = document.getElementById("turn-stage");
  if (!bridge || !vue || !host || !stage) {
    return;
  }

  const { createApp, computed, onBeforeUnmount, onMounted, ref, watch, nextTick } = vue;

  function resizeComposerTextarea(node) {
    if (!node) return;
    node.style.height = "auto";
    node.style.height = `${Math.min(node.scrollHeight, 160)}px`;
  }

  function composerActions() {
    const tools = window.__ZAOMENG_UI_BRIDGE_TOOLS__ || {};
    if (typeof tools.readLegacyActionBridge === "function") {
      return tools.readLegacyActionBridge("__ZAOMENG_COMPOSER_ACTIONS__");
    }
    return window.__ZAOMENG_COMPOSER_ACTIONS__ || {};
  }

  function normalizeKind(kind) {
    const value = String(kind || "").trim();
    if (value === "plot") return "plot";
    return value === "narration" ? "narration" : "dialogue";
  }

  createApp({
    setup() {
      const snapshot = ref(bridge.getSnapshot ? bridge.getSnapshot() : {});
      const unsubscribe = bridge.subscribe((nextSnapshot) => {
        snapshot.value = nextSnapshot || {};
      });

      onBeforeUnmount(() => {
        unsubscribe();
      });

      const composer = computed(() => snapshot.value.composer || {});
      const session = computed(() => snapshot.value.currentDialogueSession || {});
      const mode = computed(() => String(composer.value.mode || session.value.mode || session.value?.session_card?.mode || "").trim());
      const draft = ref("");
      const draftKind = ref("dialogue");
      const textareaRef = ref(null);
      const mentionOpen = ref(false);
      const mentionQuery = ref("");
      const mentionStart = ref(-1);
      const mentionEnd = ref(-1);
      const mentionIndex = ref(0);
      watch(
        () => composer.value.placeholder,
        () => {
          nextTick(() => resizeComposerTextarea(textareaRef.value));
        }
      );
      watch(
        () => composer.value.message,
        (nextMessage) => {
          const nextValue = String(nextMessage || "");
          if (nextValue !== draft.value) closeMentionMenu();
          draft.value = nextValue;
          nextTick(() => resizeComposerTextarea(textareaRef.value));
        },
        { immediate: true }
      );
      watch(
        () => composer.value.kind,
        (nextKind) => {
          draftKind.value = normalizeKind(nextKind);
        },
        { immediate: true }
      );
      watch(
        mode,
        (nextMode) => {
          if (String(nextMode || "").trim() === "observe") {
            setKind("narration");
          } else if (draftKind.value === "narration") {
            setKind("dialogue");
          }
        },
        { immediate: true }
      );
      const placeholder = computed(() => String(composer.value.placeholder || ""));
      const quickReplies = computed(() => (Array.isArray(composer.value.quickReplies) ? composer.value.quickReplies : []));
      const disabled = computed(() => Boolean(composer.value.disabled));
      const suggestHidden = computed(() => Boolean(composer.value.suggestHidden) || mode.value === "observe");
      const showKindToggle = computed(() => mode.value !== "observe");
      const suggestDisabled = computed(() => Boolean(composer.value.suggestDisabled));
      const sendDisabled = computed(() => Boolean(composer.value.sendDisabled));
      const associationEnabled = computed(() => composer.value.associationEnabled !== false);
      const mentionCandidates = computed(() => (
        Array.isArray(composer.value.mentionCandidates)
          ? composer.value.mentionCandidates.map((name) => String(name || "").trim()).filter(Boolean)
          : []
      ));
      const mentionOptions = computed(() => {
        if (!mentionOpen.value) return [];
        const query = mentionQuery.value.toLocaleLowerCase();
        return mentionCandidates.value.filter((name) => !query || name.toLocaleLowerCase().includes(query));
      });

      onMounted(() => {
        stage.classList.add("has-vue-island");
        host.classList.remove("hidden");
        nextTick(() => resizeComposerTextarea(textareaRef.value));
      });

      function setDraftValue(value, options = {}) {
        draft.value = String(value || "");
        const actions = composerActions();
        if (typeof actions.setDraft === "function") {
          actions.setDraft(draft.value, options);
        }
        nextTick(() => resizeComposerTextarea(textareaRef.value));
      }

      function onDraftInput(event) {
        const value = event?.target?.value || "";
        setDraftValue(value);
        updateMentionMenu(value, event?.target?.selectionStart);
      }

      function extractMentionContext(value, caretPosition) {
        const text = String(value || "");
        const caret = Math.max(0, Math.min(text.length, Number(caretPosition ?? text.length)));
        const beforeCaret = text.slice(0, caret);
        const start = beforeCaret.lastIndexOf("@");
        if (start < 0) return null;
        const query = beforeCaret.slice(start + 1);
        if (/[@\r\n\t，。！？；：、（）(),.!?;:]/u.test(query)) return null;
        return { start, end: caret, query };
      }

      function closeMentionMenu() {
        mentionOpen.value = false;
        mentionQuery.value = "";
        mentionStart.value = -1;
        mentionEnd.value = -1;
        mentionIndex.value = 0;
      }

      function updateMentionMenu(value, caretPosition) {
        const context = extractMentionContext(value, caretPosition);
        if (!context || !mentionCandidates.value.length) {
          closeMentionMenu();
          return;
        }
        mentionQuery.value = context.query;
        mentionStart.value = context.start;
        mentionEnd.value = context.end;
        mentionIndex.value = 0;
        mentionOpen.value = true;
        nextTick(() => {
          if (!mentionOptions.value.length) closeMentionMenu();
        });
      }

      function insertMention(name) {
        const target = String(name || "").trim();
        if (!target || !mentionCandidates.value.includes(target) || mentionStart.value < 0) return;
        const nextValue = `${draft.value.slice(0, mentionStart.value)}@${target} ${draft.value.slice(mentionEnd.value)}`;
        const nextCaret = mentionStart.value + target.length + 2;
        closeMentionMenu();
        setDraftValue(nextValue, { focus: true });
        nextTick(() => {
          textareaRef.value?.focus();
          textareaRef.value?.setSelectionRange(nextCaret, nextCaret);
        });
      }

      function setKind(nextKind) {
        draftKind.value = normalizeKind(nextKind);
        const actions = composerActions();
        if (typeof actions.setKind === "function") {
          actions.setKind(draftKind.value);
        }
      }

      function send() {
        closeMentionMenu();
        const actions = composerActions();
        if (typeof actions.send === "function") {
          const sendKind = mode.value === "observe" ? "narration" : draftKind.value;
          actions.send(draft.value, sendKind);
        }
      }

      function suggest() {
        const actions = composerActions();
        if (typeof actions.suggest === "function") {
          actions.suggest();
        }
      }

      function quickReply(value) {
        const actions = composerActions();
        if (typeof actions.quickReply === "function") {
          actions.quickReply(value);
        }
      }

      function setAssociationEnabled(event) {
        const actions = composerActions();
        if (typeof actions.setAssociationEnabled === "function") {
          actions.setAssociationEnabled(Boolean(event?.target?.checked));
        }
      }

      function handleComposerKeydown(event) {
        if (mentionOpen.value && mentionOptions.value.length) {
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            const step = event.key === "ArrowDown" ? 1 : -1;
            mentionIndex.value = (mentionIndex.value + step + mentionOptions.value.length) % mentionOptions.value.length;
            return;
          }
          if (event.key === "Enter" || event.key === "Tab") {
            event.preventDefault();
            insertMention(mentionOptions.value[mentionIndex.value]);
            return;
          }
          if (event.key === "Escape") {
            event.preventDefault();
            closeMentionMenu();
            return;
          }
        }
        if (event.key !== "Enter" || event.shiftKey) return;
        event.preventDefault();
        if (!sendDisabled.value) {
          send();
        }
      }

      return {
        disabled,
        draft,
        draftKind,
        textareaRef,
        onDraftInput,
        handleComposerKeydown,
        placeholder,
        quickReplies,
        quickReply,
        send,
        sendDisabled,
        showKindToggle,
        setDraftValue,
        setKind,
        suggest,
        suggestDisabled,
        suggestHidden,
        associationEnabled,
        setAssociationEnabled,
        insertMention,
        mentionIndex,
        mentionOptions,
      };
    },
    template: `
      <div class="composer-vue-shell">
        <div v-if="quickReplies.length" class="quick-reply-row">
          <button
            v-for="item in quickReplies"
            :key="item.label + ':' + item.value"
            type="button"
            class="quick-reply-chip"
            :disabled="disabled"
            @click="quickReply(item.value)"
          >
            {{ item.label }}
          </button>
        </div>

        <div class="composer-utility-row">
          <label class="dialogue-association-toggle-control">
            <input
              type="checkbox"
              :checked="associationEnabled"
              @change="setAssociationEnabled"
            >
            <span class="dialogue-association-toggle-track" aria-hidden="true"></span>
            <span>AI 联想</span>
          </label>
        </div>

        <div class="composer-main composer-main-vue">
          <div v-if="showKindToggle" class="composer-kind-toggle" role="group" aria-label="输入类型">
            <button
              type="button"
              class="kind-chip"
              :class="{ active: draftKind === 'dialogue' }"
              @click="setKind('dialogue')"
            >
              台词
            </button>
            <button
              type="button"
              class="kind-chip"
              :class="{ active: draftKind === 'plot' }"
              @click="setKind('plot')"
              title="可填写推进方向，也可以留空让系统主动推进"
            >
              推进剧情
            </button>
          </div>

          <div v-if="mentionOptions.length" class="composer-mention-menu" role="listbox" aria-label="选择在场人物">
            <button
              v-for="(name, index) in mentionOptions"
              :key="name"
              type="button"
              class="composer-mention-option"
              :class="{ active: index === mentionIndex }"
              :aria-selected="index === mentionIndex ? 'true' : 'false'"
              role="option"
              @mousedown.prevent="insertMention(name)"
            >
              {{ '@' + name }}
            </button>
          </div>

          <textarea
            ref="textareaRef"
            class="composer-textarea"
            rows="2"
            :value="draft"
            :placeholder="placeholder"
            :disabled="disabled"
            @input="onDraftInput"
            @keydown="handleComposerKeydown"
          ></textarea>

          <div class="composer-actions">
            <button
              v-if="!suggestHidden"
              type="button"
              class="composer-icon-button"
              aria-label="帮我续一句"
              title="帮我续一句"
              :disabled="suggestDisabled"
              @click="suggest"
            >
              ✨
            </button>
            <button
              type="button"
              class="send-button"
              :aria-label="draftKind === 'plot' ? '推进剧情' : '送出'"
              :disabled="sendDisabled"
              @click="send"
            >
              {{ draftKind === 'plot' ? '推进' : '送出' }}
            </button>
          </div>
        </div>
      </div>
    `,
  }).mount(host);
})();
