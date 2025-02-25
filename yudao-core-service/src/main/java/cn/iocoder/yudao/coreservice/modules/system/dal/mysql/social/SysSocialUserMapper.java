package cn.iocoder.yudao.coreservice.modules.system.dal.mysql.social;

import cn.iocoder.yudao.coreservice.modules.system.dal.dataobject.social.SysSocialUserDO;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

// TODO @timfruit：SysSocialUserCoreMapper 改名，方便区分
@Mapper
public interface SysSocialUserMapper extends BaseMapperX<SysSocialUserDO> {

    default List<SysSocialUserDO> selectListByTypeAndUnionId(Integer userType, Collection<Integer> types, String unionId) {
        return selectList(new QueryWrapper<SysSocialUserDO>().eq("user_type", userType)
                .in("type", types).eq("union_id", unionId));
    }

    default List<SysSocialUserDO> selectListByTypeAndUserId(Integer userType, Collection<Integer> types, Long userId) {
        return selectList(new QueryWrapper<SysSocialUserDO>().eq("user_type", userType)
                .in("type", types).eq("user_id", userId));
    }

    default List<SysSocialUserDO> selectListByUserId(Integer userType, Long userId) {
        return selectList(new QueryWrapper<SysSocialUserDO>().eq("user_type", userType).eq("user_id", userId));
    }

}
