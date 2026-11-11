# incident-recovery — 세션이 먼저 읽을 것

## README 와 그림

- `README.md` 는 한국어이고 `README.en.md` 는 영어판이다. 두 파일은 맨 위에서 서로 링크한다. `README.en.md` 는 같은 그림의 영어 문구판 `*.en.svg` 를 쓴다
- README 의 그림은 `docs/diagrams/*.svg` 이고 `<picture>` 로 넣는다. **밝은 버전만 고치고** `node docs/diagrams/make-dark.mjs <밝은 판>` 을 다시 돌려
  다크 버전(`*.dark.svg`)을 만든다 — 둘을 손으로 유지하면 어긋난다. `make-dark.mjs` 는 narrator 의 같은 파일을 그대로 복사한 것이다
- 너비는 840 (GitHub README 칸에 1:1)이다. 그림은 narrator 의 `docs/diagrams/` 와 같은 문법을 따른다. 색 토큰은 여덟
  (`#ffffff` `#262626` `#6f6f6f` `#bdbdbd` `#2b3f6b` `#e8ebf3` `#cfd6e6` `#b23a1d`)이고, 둥근 모서리를 쓰지 않으며, 선의 뜻은 색이 아니라
  무늬로 나눈다. 대응표에 없는 색을 쓰면 `make-dark.mjs` 가 멈춘다
- 선 라벨과 범례는 10.5px, 그 밖의 글자는 11px 아래로 내리지 않는다
- 그림 안의 문구(상자 역할, 선 라벨, 제목, 메모, alt)는 Gemini 가 쓰고 사실과 상자 넘침을 대조해 넣는다
