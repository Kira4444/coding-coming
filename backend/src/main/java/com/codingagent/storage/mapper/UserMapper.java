package com.codingagent.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codingagent.storage.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
