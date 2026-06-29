package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
@Api(tags = "关注接口")
public class FollowController {
    @Resource
    private IFollowService followService;
    @PutMapping("/{id}/{isFollow}")
    @ApiOperation("尝试关注接口")
    public Result follow(@PathVariable("id") Long followUserId,@PathVariable("isFollow") Boolean isFollow){
        return followService.follow(followUserId,isFollow);
    }
    @GetMapping("/or/not/{id}")
    @ApiOperation("尝试关注接口")
    public Result followOrNot(@PathVariable("id") Long followUserId){
        return followService.followOrNot(followUserId);
    }
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id){
        return followService.followCommons(id);
    }
}
