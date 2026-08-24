-- AuthUser(Session Principal)의 직렬화 형식이 변경됐다(serialVersionUID 명시 + 필드 추가).
-- 기존 세션은 자동 계산된 UID로 저장되어 역직렬화에 실패하므로 명시적으로 만료시킨다.
-- 사용자는 재로그인만 하면 된다. SPRING_SESSION 테이블은 spring-session이 앱 기동 시
-- 생성하므로(새 DB에는 아직 없음) 존재할 때만 삭제한다.
DO $$
BEGIN
    IF to_regclass('spring_session') IS NOT NULL THEN
        DELETE FROM spring_session_attributes;
        DELETE FROM spring_session;
    END IF;
END $$;
