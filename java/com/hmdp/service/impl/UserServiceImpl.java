package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final UserMapper userMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if(phoneInvalid){
            return Result.fail("手机号格式错误!");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session --> 修改为key为手机号，value为验证码，并设置有效期
        //session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("发送短信验证码成功，验证码:{}",code);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        String key = USER_SIGN_KEY + userId + format;
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截至今天为止的的签到记录,返回的是十进制数字 BITFIELD sing:xx:yyyy/MM
        List<Long> longs = stringRedisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(longs == null || longs.isEmpty()){
            return Result.ok(0);//第一次判断边界条件
        }
        Long l = longs.get(0);
        if(l == null || l == 0){
            return Result.ok(0);//第二次判断边界条件
        }
        int count = 0;
        //循环遍历,让该数字与1做与运算，得到数字的最后一个bit位
        while (true){
            if((l & 1) == 0){
                //未签到，结束循环
                break;
            }else{
                count++;
                l >>>= 1;
            }
        }
        //如果为0，未签到，结束，不为0，计数器+1，数字右移一位，抛弃最后一位，继续循环
        return Result.ok(count);
    }

    /**
     * 签到功能实现
     * @return
     */
    @Override
    public Result sign() {
        //1.获取当前用户的信息（作为key）
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        String key = USER_SIGN_KEY + userId + format;//再来个sign:作为前缀
        int dayOfMonth = now.getDayOfMonth();
        dayOfMonth -= 1;
        //写入bitmap
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth,true);
        return Result.ok();
    }

    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误!!");
        }
        //2.校验验证码 -> 从Redis里获取
        //Object cacheCode = session.getAttribute("code");
        String phone = loginForm.getPhone();
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            //3.不一致，报错
            return Result.fail("验证码错误");
        }
        //4.一致，根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        if(user == null){
            //5.判断用户是否存在
            user = createUserWithPhone(loginForm.getPhone());
        }
        //保存用户信息到redis中
        String token = UUID.randomUUID().toString(true);
        //将userDTO对象转换成Map
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>()
        , CopyOptions.create()
                        .ignoreNullValue()
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
        //设置有效期并存储
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token,userMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,RedisConstants.LOGIN_USER_TTL_TRUE,TimeUnit.SECONDS);
        //session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
        //返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
