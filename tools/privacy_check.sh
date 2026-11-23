#!/usr/bin/env bash
# 저장소에 개인 건강 정보·비밀 값이 들어가지 않았는지 검사한다.
# 찾은 내용은 출력하지 않고 파일:줄 위치만 보여 준다 (Actions 로그가 공개될 수 있으므로).
# GitHub Actions에서 빌드 전에 실행하고, 올리기 전에 PC에서도 돌려 볼 수 있다.
#   bash tools/privacy_check.sh
# 추가로 막고 싶은 단어(이름·병원·약 이름 등)는 private/blocklist.txt 에 한 줄씩 적는다.
# private/ 폴더는 .gitignore 로 막혀 있어 저장소에는 올라가지 않는다.
set -u
cd "$(git rev-parse --show-toplevel)"
fail=0

files=$(git ls-files)

# 1) 데이터·키 파일 형식
bad=$(echo "$files" | grep -iE '\.(html?|csv|xlsx?|pdf|db|sqlite|keystore|jks|apk)$' || true)
if [ -n "$bad" ]; then
  echo "::error::데이터/키 파일이 저장소에 있습니다:"; echo "$bad"; fail=1
fi

# 2) API 키 모양
if git grep -nE 'sk-ant-[A-Za-z0-9_-]{10,}' -- . ':!tools/privacy_check.sh' >/dev/null; then
  echo "::error::Claude API 키로 보이는 문자열이 있습니다 (위치만 표시):"
  git grep -nE 'sk-ant-[A-Za-z0-9_-]{10,}' -- . ':!tools/privacy_check.sh' | cut -d: -f1,2; fail=1
fi

# 3) 주민등록번호 모양
if git grep -nE '[0-9]{6}-[1-4][0-9]{6}' -- . >/dev/null; then
  echo "::error::주민등록번호로 보이는 문자열이 있습니다 (위치만 표시):"
  git grep -nE '[0-9]{6}-[1-4][0-9]{6}' -- . | cut -d: -f1,2; fail=1
fi

# 4) 로컬 금지어 목록 (PC에서만)
if [ -f private/blocklist.txt ]; then
  while IFS= read -r w; do
    [ -z "$w" ] && continue
    if git grep -nF -- "$w" >/dev/null; then
      echo "금지어가 저장소에 있습니다:"; git grep -nF -- "$w" | cut -d: -f1,2; fail=1
    fi
  done < private/blocklist.txt
fi

if [ $fail -eq 0 ]; then echo "개인정보 검사 통과"; fi
exit $fail
