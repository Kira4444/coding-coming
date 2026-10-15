package com.codingagent.web;

import com.codingagent.storage.entity.McpServer;
import com.codingagent.storage.entity.Message;
import com.codingagent.storage.entity.Project;
import com.codingagent.storage.entity.Session;
import com.codingagent.storage.entity.Skill;
import com.codingagent.storage.mapper.McpServerMapper;
import com.codingagent.storage.mapper.MessageMapper;
import com.codingagent.storage.mapper.ProjectMapper;
import com.codingagent.storage.mapper.SessionMapper;
import com.codingagent.storage.mapper.SkillMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 级联删除的顺序是承重的：message 指着 session、session/mcp/skill 指着 project，
 * 删反了一步就抛外键违约，最后被兜底 handler 说成「服务内部错误」。
 * 这个测试锁住删除顺序——项目及其下会话、消息、MCP、Skill 必须能一并删干净。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cascade-del-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql",
        "spring.sql.init.continue-on-error=true",
        "coding-agent.secret-key=cascade-test-key-not-for-prod",
        "coding-agent.sandbox.driver=local",
})
class ProjectCascadeDeleteTest {

    @Autowired
    SessionService sessionService;
    @Autowired
    ProjectMapper projectMapper;
    @Autowired
    SessionMapper sessionMapper;
    @Autowired
    MessageMapper messageMapper;
    @Autowired
    McpServerMapper mcpServerMapper;
    @Autowired
    SkillMapper skillMapper;

    @TempDir
    Path temp;

    @Test
    void 删除项目时会话消息MCP与Skill一并清掉() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("sess_ws"));

        Project p = new Project();
        p.setOwnerId(1L);
        p.setName("cascade-del-test");
        p.setRepoType("LOCAL_PATH");
        p.setLocalPath(temp.toString());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectMapper.insert(p);

        Session s = new Session();
        s.setId("sess_cascadedel");
        s.setProjectId(p.getId());
        s.setUserId(1L);
        s.setTitle("cascade-del-test");
        s.setWorkspacePath(ws.toString());
        s.setBranchName("coding-agent/cascade-del-test");
        s.setStatus("RUNNING");
        s.setWorkspacePurged(false);
        s.setPromptTokens(0L);
        s.setCompletionTokens(0L);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        sessionMapper.insert(s);

        Message m = new Message();
        m.setSessionId(s.getId());
        m.setRole("user");
        m.setContent("cascade-del-test");
        messageMapper.insert(m);

        McpServer mcp = new McpServer();
        mcp.setProjectId(p.getId());
        mcp.setName("cascade-del-test");
        mcp.setTransport("stdio");
        mcp.setCommand("node");
        mcpServerMapper.insert(mcp);

        Skill skill = new Skill();
        skill.setProjectId(p.getId());
        skill.setName("cascade-del-test");
        skill.setContent("noop");
        skill.setEnabled(true);
        skillMapper.insert(skill);

        sessionService.deleteProject(p.getId(), 1L);

        assertEquals(0, messageMapper.selectCount(null), "message 必须先于 session 清掉");
        assertEquals(0, sessionMapper.selectCount(null), "session 必须删掉");
        assertEquals(0, mcpServerMapper.selectCount(null), "project_mcp_server 必须先于 project 清掉");
        assertEquals(0, skillMapper.selectCount(null), "project_skill 必须先于 project 清掉");
        assertEquals(0, projectMapper.selectCount(null), "project 必须删掉");
    }
}
