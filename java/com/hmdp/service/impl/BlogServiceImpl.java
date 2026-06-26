package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService iUserService;
    @Resource
    private IBlogService blogService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryHotBlogById(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.user2Blog(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.判断当前用户是否点赞
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Boolean member = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        //如果未点赞，可以点赞，数据库点赞数加一，保存用户到redis的set集合
        if(BooleanUtil.isFalse(member)){
            //数据库点赞数加一
            boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
            if(update){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }
        }else{
            //如果已点赞，可以取消，数据库点赞数减一，把用户从redis的set集合里面删除
            boolean update = update().setSql("liked = liked - 1").eq("id", id).update();
            if(update){
                stringRedisTemplate.opsForSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在！");
        }
        user2Blog(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void user2Blog(Blog blog) {
        Long userId = blog.getUserId();
        User user = iUserService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    private void isBlogLiked(Blog blog){
        //查询blog是否被当前用户点过赞，并且为blog的isliked属性赋值。
        UserDTO user = UserHolder.getUser();
        if(user == null){
            blog.setIsLike(false);
            return;
        }
        Long userId = user.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Boolean member = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(member));
    }
}
