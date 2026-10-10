// Text-to-speech wrapper (Web Speech API).
// Two voices: Willow's calm narrator, and a stand-in for each video's own audio.

const synth = window.speechSynthesis;
let voices = [];
let enabled = true;
let generation = 0;

function loadVoices() {
  voices = synth ? synth.getVoices().filter((v) => /zh[-_](CN|Hans)|Chinese|普通话/i.test(`${v.lang} ${v.name}`)) : [];
}
loadVoices();
synth?.addEventListener?.('voiceschanged', loadVoices);

function findVoice(preferences, avoid) {
  for (const name of preferences) {
    const v = voices.find((x) => x.name.includes(name) && x !== avoid);
    if (v) return v;
  }
  return voices.find((x) => x !== avoid) || voices[0] || null;
}

function narratorVoice() {
  return findVoice(['Xiaoxiao', 'Google 普通话', 'Huihui', 'Yaoyao']);
}

function videoVoice() {
  return findVoice(['Yunxi', 'Kangkang', 'Yunyang', 'Google 普通话', 'Huihui'], narratorVoice());
}

export const voice = {
  get available() { return Boolean(synth); },
  get speaking() { return Boolean(synth?.speaking); },
  get enabled() { return enabled; },
  set enabled(v) { enabled = v; if (!v) this.stop(); },

  stop() {
    generation++;
    synth?.cancel();
  },

  // Resolves when finished (or stopped). Never rejects.
  say(text, { role = 'narrator', dramatic = false } = {}) {
    if (!synth || !enabled || !text) return Promise.resolve(false);
    const myGeneration = generation;
    return new Promise((resolve) => {
      const u = new SpeechSynthesisUtterance(text);
      u.lang = 'zh-CN';
      u.voice = role === 'narrator' ? narratorVoice() : videoVoice();
      u.rate = role === 'narrator' ? 0.85 : dramatic ? 1.2 : 1.0;
      u.pitch = dramatic ? 0.7 : 1.0;
      let done = false;
      const finish = () => { if (!done) { done = true; resolve(myGeneration === generation); } };
      u.onend = finish;
      u.onerror = finish;
      // Some browsers occasionally never fire onend; don't let the flow hang.
      setTimeout(finish, 2500 + text.length * 380);
      synth.speak(u);
    });
  },
};
