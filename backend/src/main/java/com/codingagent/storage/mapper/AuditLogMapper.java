package com.codingagent.storage.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 审计日志写入。
 *
 * <p>只写不改：审计记录一旦落库就不应被修改，所以不继承 {@code BaseMapper}，
 * 避免误开 update/delete 的口子。
 */
@Mapper
public interface AuditLogMapper {

    @Insert("""
            INSERT INTO audit_log (session_id, user_id, action, tool_name, detail, approved)
            VALUES (#{sessionId}, #{userId}, #{action}, #{toolName}, #{detail}, #{approved})
            """)
    void insertLog(@Param("sessionId") String sessionId,
                   @Param("userId") Long userId,
                   @Param("action") String action,
                   @Param("toolName") String toolName,
                   @Param("detail") String detail,
                   @Param("approved") Boolean approved);
}
