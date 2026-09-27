#!/usr/bin/env node
/**
 * **병합이 끝난 가지에 커밋을 더 올리는 것을 막는다.**
 *
 * ## 왜 있는가
 *
 * PR 이 병합된 뒤 같은 가지에 커밋을 올리면 **그 커밋은 아무 PR 에도 담기지 않는다.**
 * `git push` 는 성공하고 경고도 없어서, **다 됐다고 믿고 넘어간다.** 나중에 「그 수정이
 * 어디 갔지」가 되는데, 그때는 무엇이 빠졌는지 찾기가 어렵다.
 *
 * **이 저장소에서도 겪었다.** 2026-09-27, PR #10 이 전날 병합됐는데 그 가지에 세 커밋을
 * 더 올렸다 — 세로축 고정↔자동, 첫 화면 깜빡임 수정, 스펙트로그램 방향. 셋 다 `main` 에
 * 없는 채로 떠 있었고, 담당자가 「PR은 없는거죠?」라고 묻고서야 알았다.
 *
 * 옆 저장소(SVT ONE)에서는 이미 네 번 겪고 이 훅을 만들었다(#781→#782, #796→#797,
 * #755, #702). **기억에 적어 두는 것으로는 막히지 않는다** — 비밀값을 훅으로 막는 것과
 * 같은 이유로, 사람이 기억하는 대신 기계가 보게 한다. 그 훅을 여기로 가져왔다.
 *
 * ## 무엇을 하는가
 *
 * 지금 가지의 PR 상태를 `gh` 로 물어, **MERGED·CLOSED** 면 밀기를 멈춘다.
 * 열려 있거나(OPEN) PR 이 아직 없으면 그냥 지나간다.
 *
 * `gh` 가 없거나 로그인이 안 돼 있으면 **막지 않는다** — 확인할 수 없는 것을 이유로
 * 일을 세우면, 검사를 끄게 되고 그러면 아무것도 막지 못한다.
 *
 * 켜는 방법(복제한 뒤 한 번): git config core.hooksPath .githooks
 * 급할 때 건너뛰기: git push --no-verify
 */
import { execFileSync } from 'node:child_process';

/** 실패해도 흐름을 세우지 않는 조용한 실행 — 없는 도구·로그인 안 된 상태를 넘긴다. */
function run(cmd, args) {
  try {
    return execFileSync(cmd, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    return null;
  }
}

const branch = run('git', ['rev-parse', '--abbrev-ref', 'HEAD']);
if (!branch || branch === 'HEAD' || branch === 'main') process.exit(0);

const raw = run('gh', ['pr', 'view', branch, '--json', 'number,state,url']);
if (!raw) {
  // PR 이 없거나 gh 를 쓸 수 없다 — 둘 다 막을 일이 아니다.
  process.exit(0);
}

let pr;
try {
  pr = JSON.parse(raw);
} catch {
  process.exit(0);
}

if (pr.state === 'OPEN') process.exit(0);

const closedWord = pr.state === 'MERGED' ? '병합' : '닫힘';
console.error('');
console.error(`✗ 이 가지의 PR 이 이미 ${closedWord} 상태입니다 — 밀기를 멈춥니다.`);
console.error('');
console.error(`  가지 : ${branch}`);
console.error(`  PR   : #${pr.number} (${pr.state})  ${pr.url ?? ''}`);
console.error('');
console.error('지금 밀면 **이 커밋은 아무 PR 에도 담기지 않습니다.** push 는 성공하고');
console.error('경고도 없어서, 다 된 줄 알고 넘어가게 됩니다(2026-09-27 이 저장소에서 겪음).');
console.error('');
console.error('이렇게 하세요 — 새 가지로 옮겨 새 PR 을 올립니다.');
console.error('');
console.error('  git log --oneline origin/main..HEAD        # 옮길 커밋 확인');
console.error('  git checkout main && git pull');
console.error('  git checkout -b <타입>/<이름>');
console.error('  git cherry-pick <커밋>                      # 위에서 본 커밋들');
console.error('  git push -u origin <타입>/<이름>');
console.error('');
console.error('정말 이 가지에 밀어야 한다면: git push --no-verify');
console.error('');
process.exit(1);
