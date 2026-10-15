package com.codingagent.config;

import com.codingagent.auth.PasswordHasher;
import com.codingagent.storage.entity.User;
import com.codingagent.storage.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时初始化管理员账号。
 *
 * <p>设计里定的是「内置账号密码，管理员初始化」。外键要求 user 表必须有行，
 * 否则项目/会话都建不出来。
 *
 * <p>只做「表为空则播种」，不覆盖已有数据——重复启动是幂等的。
 */
@Component
public class DataBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataBootstrap.class);

    /** 默认管理员用户名 */
    static final String DEFAULT_USERNAME = "admin";

    /** 默认口令。首次登录后必须改掉——设置页提供了改密入口。 */
    static final String DEFAULT_PASSWORD = "admin123";

    private final UserMapper userMapper;

    public DataBootstrap(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(Wrappers.emptyWrapper());
        if (count != null && count > 0) {
            log.debug("用户表已有 {} 条记录，跳过初始化", count);
            return;
        }

        User admin = new User();
        admin.setUsername(DEFAULT_USERNAME);
        admin.setPasswordHash(PasswordHasher.hash(DEFAULT_PASSWORD));
        admin.setDisplayName("管理员");
        admin.setRole("ADMIN");
        userMapper.insert(admin);

        log.warn("已初始化默认账号 [{}] / [{}] id={}。请登录后立即在设置页改掉默认口令。",
                DEFAULT_USERNAME, DEFAULT_PASSWORD, admin.getId());
    }
}
