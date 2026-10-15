package com.codingagent.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codingagent.storage.entity.Project;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
