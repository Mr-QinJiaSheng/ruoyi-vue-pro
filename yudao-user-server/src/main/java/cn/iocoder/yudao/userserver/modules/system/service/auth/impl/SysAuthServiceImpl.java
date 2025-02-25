package cn.iocoder.yudao.userserver.modules.system.service.auth.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.iocoder.yudao.coreservice.modules.member.dal.dataobject.user.MbrUserDO;
import cn.iocoder.yudao.coreservice.modules.system.dal.dataobject.social.SysSocialUserDO;
import cn.iocoder.yudao.coreservice.modules.system.enums.logger.SysLoginLogTypeEnum;
import cn.iocoder.yudao.coreservice.modules.system.enums.logger.SysLoginResultEnum;
import cn.iocoder.yudao.coreservice.modules.system.service.auth.SysUserSessionCoreService;
import cn.iocoder.yudao.coreservice.modules.system.service.logger.SysLoginLogCoreService;
import cn.iocoder.yudao.coreservice.modules.system.service.logger.dto.SysLoginLogCreateReqDTO;
import cn.iocoder.yudao.coreservice.modules.system.service.social.SysSocialService;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.userserver.modules.member.service.user.MbrUserService;
import cn.iocoder.yudao.userserver.modules.system.controller.auth.vo.*;
import cn.iocoder.yudao.userserver.modules.system.convert.auth.SysAuthConvert;
import cn.iocoder.yudao.userserver.modules.system.enums.sms.SysSmsSceneEnum;
import cn.iocoder.yudao.userserver.modules.system.service.auth.SysAuthService;
import cn.iocoder.yudao.userserver.modules.system.service.sms.SysSmsCodeService;
import lombok.extern.slf4j.Slf4j;
import me.zhyd.oauth.model.AuthUser;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.userserver.modules.system.enums.SysErrorCodeConstants.*;

/**
 * Auth Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Slf4j
public class SysAuthServiceImpl implements SysAuthService {

    @Resource
    @Lazy // 延迟加载，因为存在相互依赖的问题
    private AuthenticationManager authenticationManager;

    @Resource
    private MbrUserService userService;
    @Resource
    private SysSmsCodeService smsCodeService;
    @Resource
    private SysLoginLogCoreService loginLogCoreService;
    @Resource
    private SysUserSessionCoreService userSessionCoreService;
    @Resource
    private SysSocialService socialService;
    private static final UserTypeEnum userTypeEnum = UserTypeEnum.MEMBER;

    @Override
    public UserDetails loadUserByUsername(String mobile) throws UsernameNotFoundException {
        // 获取 username 对应的 SysUserDO
        MbrUserDO user = userService.getUserByMobile(mobile);
        if (user == null) {
            throw new UsernameNotFoundException(mobile);
        }
        // 创建 LoginUser 对象
        return SysAuthConvert.INSTANCE.convert(user);
    }

    @Override
    public String login(SysAuthLoginReqVO reqVO, String userIp, String userAgent) {
        // 使用手机 + 密码，进行登录。
        LoginUser loginUser = this.login0(reqVO.getMobile(), reqVO.getPassword());

        // 缓存登录用户到 Redis 中，返回 sessionId 编号
        return userSessionCoreService.createUserSession(loginUser, userIp, userAgent);
    }

    @Override
    @Transactional
    public String smsLogin(SysAuthSmsLoginReqVO reqVO, String userIp, String userAgent) {
        // 校验验证码
        smsCodeService.useSmsCode(reqVO.getMobile(), SysSmsSceneEnum.LOGIN_BY_SMS.getScene(),
                reqVO.getCode(), userIp);

        // 获得获得注册用户
        MbrUserDO user = userService.createUserIfAbsent(reqVO.getMobile(), userIp);
        Assert.notNull(user, "获取用户失败，结果为空");

        // 执行登陆
        this.createLoginLog(user.getMobile(), SysLoginLogTypeEnum.LOGIN_SMS, SysLoginResultEnum.SUCCESS);
        LoginUser loginUser = SysAuthConvert.INSTANCE.convert(user);

        // 缓存登录用户到 Redis 中，返回 sessionId 编号
        return userSessionCoreService.createUserSession(loginUser, userIp, userAgent);
    }

    @Override
    public String socialLogin(MbrAuthSocialLoginReqVO reqVO, String userIp, String userAgent) {
        // 使用 code 授权码，进行登录
        AuthUser authUser = socialService.getAuthUser(reqVO.getType(), reqVO.getCode(), reqVO.getState());
        org.springframework.util.Assert.notNull(authUser, "授权用户不为空");

        // 如果未绑定 SysSocialUserDO 用户，则无法自动登录，进行报错
        String unionId = socialService.getAuthUserUnionId(authUser);
        List<SysSocialUserDO> socialUsers = socialService.getAllSocialUserList(reqVO.getType(), unionId, userTypeEnum);
        if (CollUtil.isEmpty(socialUsers)) {
            throw exception(AUTH_THIRD_LOGIN_NOT_BIND);
        }

        // 自动登录
        MbrUserDO user = userService.getUser(socialUsers.get(0).getUserId());
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        this.createLoginLog(user.getMobile(), SysLoginLogTypeEnum.LOGIN_SOCIAL, SysLoginResultEnum.SUCCESS);

        // 创建 LoginUser 对象
        LoginUser loginUser = SysAuthConvert.INSTANCE.convert(user);

        // 绑定社交用户（更新）
        socialService.bindSocialUser(loginUser.getId(), reqVO.getType(), authUser, userTypeEnum);

        // 缓存登录用户到 Redis 中，返回 sessionId 编号
        return userSessionCoreService.createUserSession(loginUser, userIp, userAgent);
    }

    @Override
    public String socialLogin2(MbrAuthSocialLogin2ReqVO reqVO, String userIp, String userAgent) {
        // 使用 code 授权码，进行登录
        AuthUser authUser = socialService.getAuthUser(reqVO.getType(), reqVO.getCode(), reqVO.getState());
        org.springframework.util.Assert.notNull(authUser, "授权用户不为空");

        // 使用账号密码，进行登录。
        LoginUser loginUser = this.login0(reqVO.getUsername(), reqVO.getPassword());
//        loginUser.setRoleIds(this.getUserRoleIds(loginUser.getId())); // 获取用户角色列表

        // 绑定社交用户（新增）
        socialService.bindSocialUser(loginUser.getId(), reqVO.getType(), authUser, userTypeEnum);

        // 缓存登录用户到 Redis 中，返回 sessionId 编号
        return userSessionCoreService.createUserSession(loginUser, userIp, userAgent);
    }

    @Override
    public void socialBind(Long userId, MbrAuthSocialBindReqVO reqVO) {
        // 使用 code 授权码，进行登录
        AuthUser authUser = socialService.getAuthUser(reqVO.getType(), reqVO.getCode(), reqVO.getState());
        org.springframework.util.Assert.notNull(authUser, "授权用户不为空");

        // 绑定社交用户（新增）
        socialService.bindSocialUser(userId, reqVO.getType(), authUser, userTypeEnum);
    }

    private LoginUser login0(String username, String password) {
        final SysLoginLogTypeEnum logTypeEnum = SysLoginLogTypeEnum.LOGIN_USERNAME;
        // 用户验证
        Authentication authentication;
        try {
            // 调用 Spring Security 的 AuthenticationManager#authenticate(...) 方法，使用账号密码进行认证
            // 在其内部，会调用到 loadUserByUsername 方法，获取 User 信息
            authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (BadCredentialsException badCredentialsException) {
            this.createLoginLog(username, logTypeEnum, SysLoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        } catch (DisabledException disabledException) {
            this.createLoginLog(username, logTypeEnum, SysLoginResultEnum.USER_DISABLED);
            throw exception(AUTH_LOGIN_USER_DISABLED);
        } catch (AuthenticationException authenticationException) {
            log.error("[login0][username({}) 发生未知异常]", username, authenticationException);
            this.createLoginLog(username, logTypeEnum, SysLoginResultEnum.UNKNOWN_ERROR);
            throw exception(AUTH_LOGIN_FAIL_UNKNOWN);
        }
        // 登录成功的日志
        Assert.notNull(authentication.getPrincipal(), "Principal 不会为空");
        this.createLoginLog(username, logTypeEnum, SysLoginResultEnum.SUCCESS);
        return (LoginUser) authentication.getPrincipal();
    }

    private void createLoginLog(String mobile, SysLoginLogTypeEnum logTypeEnum, SysLoginResultEnum loginResult) {
        // 获得用户
        MbrUserDO user = userService.getUserByMobile(mobile);
        // 插入登录日志
        SysLoginLogCreateReqDTO reqDTO = new SysLoginLogCreateReqDTO();
        reqDTO.setLogType(logTypeEnum.getType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        if (user != null) {
            reqDTO.setUserId(user.getId());
        }
        reqDTO.setUsername(mobile);
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(ServletUtils.getClientIP());
        reqDTO.setResult(loginResult.getResult());
        loginLogCoreService.createLoginLog(reqDTO);
        // 更新最后登录时间
        if (user != null && Objects.equals(SysLoginResultEnum.SUCCESS.getResult(), loginResult.getResult())) {
            userService.updateUserLogin(user.getId(), ServletUtils.getClientIP());
        }
    }

    @Override
    public LoginUser verifyTokenAndRefresh(String token) {
        // 获得 LoginUser
        LoginUser loginUser = userSessionCoreService.getLoginUser(token);
        if (loginUser == null) {
            return null;
        }
        // 刷新 LoginUser 缓存
        this.refreshLoginUserCache(token, loginUser);
        return loginUser;
    }

    private void refreshLoginUserCache(String token, LoginUser loginUser) {
        // 每 1/3 的 Session 超时时间，刷新 LoginUser 缓存
        if (System.currentTimeMillis() - loginUser.getUpdateTime().getTime() <
                userSessionCoreService.getSessionTimeoutMillis() / 3) {
            return;
        }

        // 重新加载 MbrUserDO 信息
        MbrUserDO user = userService.getUser(loginUser.getId());
        if (user == null || CommonStatusEnum.DISABLE.getStatus().equals(user.getStatus())) {
            throw exception(AUTH_TOKEN_EXPIRED); // 校验 token 时，用户被禁用的情况下，也认为 token 过期，方便前端跳转到登录界面
        }

        // 刷新 LoginUser 缓存
        userSessionCoreService.refreshUserSession(token, loginUser);
    }

    @Override
    public LoginUser mockLogin(Long userId) {
        // 获取用户编号对应的 MbrUserDO
        MbrUserDO user = userService.getUser(userId);
        if (user == null) {
            throw new UsernameNotFoundException(String.valueOf(userId));
        }

        // 执行登陆
        this.createLoginLog(user.getMobile(), SysLoginLogTypeEnum.LOGIN_MOCK, SysLoginResultEnum.SUCCESS);

        // 创建 LoginUser 对象
        return SysAuthConvert.INSTANCE.convert(user);
    }

    @Override
    public void logout(String token) {
        // 查询用户信息
        LoginUser loginUser = userSessionCoreService.getLoginUser(token);
        if (loginUser == null) {
            return;
        }
        // 删除 session
        userSessionCoreService.deleteUserSession(token);
        // 记录登出日志
        this.createLogoutLog(loginUser.getId(), loginUser.getUsername());
    }

    private void createLogoutLog(Long userId, String username) {
        SysLoginLogCreateReqDTO reqDTO = new SysLoginLogCreateReqDTO();
        reqDTO.setLogType(SysLoginLogTypeEnum.LOGOUT_SELF.getType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(userTypeEnum.getValue());
        reqDTO.setUsername(username);
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(ServletUtils.getClientIP());
        reqDTO.setResult(SysLoginResultEnum.SUCCESS.getResult());
        loginLogCoreService.createLoginLog(reqDTO);
    }

}
