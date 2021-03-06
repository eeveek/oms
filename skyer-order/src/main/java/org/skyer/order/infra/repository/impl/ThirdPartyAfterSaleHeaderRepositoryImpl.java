package org.skyer.order.infra.repository.impl;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.skyer.boot.platform.lov.annotation.ProcessLovValue;
import org.skyer.core.domain.Page;
import org.skyer.core.exception.CommonException;
import org.skyer.core.util.EncryptionUtils;
import org.skyer.mybatis.base.impl.BaseRepositoryImpl;
import org.skyer.mybatis.domian.Condition;
import org.skyer.mybatis.pagehelper.PageHelper;
import org.skyer.mybatis.util.Sqls;
import org.skyer.order.api.dto.ThirdPartyAfterSaleDTO;
import org.skyer.order.api.dto.ThirdPartyAfterSalePageDTO;
import org.skyer.order.api.dto.ThirdPartyReceiveInfoDTO;
import org.skyer.order.api.dto.ThirdPartyRefundBaseInfo;
import org.skyer.order.api.vo.ThirdPartyAfterSaleHeaderDetailsVO;
import org.skyer.order.api.vo.ThirdPartyAfterSalePageVO;
import org.skyer.order.app.service.saga.ThirdPartyAfterSaleSagaReturnService;
import org.skyer.order.app.service.saga.ThirdRefundSagaService;
import org.skyer.order.domain.entity.ThirdPartyAfterReceiverInfo;
import org.skyer.order.domain.entity.ThirdPartyAfterSaleHeader;
import org.skyer.order.domain.entity.ThirdPartyAfterSaleItem;
import org.skyer.order.domain.entity.ThirdPartyReturnLogistics;
import org.skyer.order.domain.repository.*;
import org.skyer.order.infra.common.CommonConstants;
import org.skyer.order.infra.convertor.ThirdPartyAfterSaleConvertor;
import org.skyer.order.infra.mapper.ThirdPartyAfterSaleHeaderMapper;

/**
 * ????????????????????? ???????????????
 *
 * @author lzh 2022-04-20 16:30:30
 */
@Component
@Slf4j
public class ThirdPartyAfterSaleHeaderRepositoryImpl extends BaseRepositoryImpl<ThirdPartyAfterSaleHeader> implements ThirdPartyAfterSaleHeaderRepository {
	private static final Logger logger = LoggerFactory.getLogger(ThirdPartyAfterSaleHeaderRepositoryImpl.class);

	@Autowired
	private ThirdPartyAfterSaleConvertor thirdPartyAfterSaleConvertor;
	@Autowired
	private RedissonClient redissonClient;
	@Autowired
	private ThirdPartyAfterSaleItemRepository thirdPartyAfterSaleItemRepository;
	@Autowired
	private ThirdPartyAfterReceiverInfoRepository thirdPartyAfterReceiverInfoRepository;
	@Autowired
	private ThirdPartyReturnLogisticsRepository thirdPartyReturnLogisticsRepository;
	@Autowired
	private ThirdPartyAfterSaleHeaderMapper thirdPartyAfterSaleHeaderMapper;
	@Autowired
	private ThirdPartyAfterSaleSagaReturnService thirdPartyAfterSaleSagaReturnService;
	@Autowired
	private ThirdRefundSagaService thirdRefundSagaService;

	/**
	 * ???????????????????????????
	 * @param thirdPartyAfterSaleDTO ?????????????????????DTO
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public ThirdPartyAfterSaleHeader createThirdPartyRefund(ThirdPartyAfterSaleDTO thirdPartyAfterSaleDTO) {
		ThirdPartyAfterSaleHeader thirdPartyAfterSaleHeader =null;
		// ?????????????????????
		ThirdPartyRefundBaseInfo refundBaseInfo = thirdPartyAfterSaleDTO.getThirdPartyRefundBaseInfo();
		String outRefundNo = refundBaseInfo.getOutRefundNo();
		RLock rlock = redissonClient.getLock(CommonConstants.AFTER_SALE_CREATE_KEY+outRefundNo);
		try {
			boolean lock = rlock.tryLock(5, 10, TimeUnit.SECONDS);
			if (lock){
				thirdPartyAfterSaleHeader = thirdPartyAfterSaleConvertor.BaseInfoconvertToHeader(refundBaseInfo);
				List<ThirdPartyAfterSaleItem> thirdPartyAfterSaleGoodsList = thirdPartyAfterSaleDTO.getThirdPartyAfterSaleGoodsList();
				AtomicReference<Integer> goodsCount = new AtomicReference<>(0);
				AtomicReference<BigDecimal> totalAmount = new AtomicReference<BigDecimal>(new BigDecimal(0));
				List<ThirdPartyAfterSaleItem> partyAfterSaleItems = thirdPartyAfterSaleGoodsList.stream().map(x -> {
					goodsCount.updateAndGet(v -> v + x.getQuantity().intValue());
					totalAmount.updateAndGet(v -> v.add(x.getTotalAmount()));
					ThirdPartyAfterSaleItem item = new ThirdPartyAfterSaleItem();
					BeanUtils.copyProperties(x, item);
					item.setOutRefundNo(refundBaseInfo.getOutRefundNo());
					item.setOuterOrderNo(refundBaseInfo.getOuterOrderNo());
					return item;
				}).collect(Collectors.toList());
				// ??????????????????
				thirdPartyAfterSaleHeader.setTotalAmount(totalAmount.get());
				thirdPartyAfterSaleHeader.setGoodsQuantity(Long.valueOf(goodsCount.get()));
				thirdPartyAfterSaleHeader.setOutReceiveInfo(JSON.toJSONString(thirdPartyAfterSaleDTO.toString()));
				thirdPartyAfterSaleHeader.setOutReceiveInfoMd5(EncryptionUtils.MD5.encrypt(JSON.toJSONString(thirdPartyAfterSaleDTO.toString())));
				// ????????????????????????????????????
				thirdPartyAfterSaleHeader.setExceptionStatus(CommonConstants.STR_NUM_ZERO);
				// ????????????????????????
				ThirdPartyReceiveInfoDTO thirdPartyReceiveInfo = thirdPartyAfterSaleDTO.getThirdPartyReceiveInfo();
				if (thirdPartyReceiveInfo != null) {
					ThirdPartyAfterReceiverInfo thirdPartyAfterReceiverInfo = thirdPartyAfterSaleConvertor.converToReceiverInfo(thirdPartyReceiveInfo);
					thirdPartyAfterReceiverInfo.setOuterRefundNo(refundBaseInfo.getOutRefundNo());
					thirdPartyAfterReceiverInfo.setOuterOrderNo(refundBaseInfo.getOuterOrderNo());
					thirdPartyAfterReceiverInfoRepository.insert(thirdPartyAfterReceiverInfo);
				}
				// ?????????????????????
				ThirdPartyReturnLogistics thirdPartyAfterSaleLogistics = thirdPartyAfterSaleDTO.getThirdPartyAfterSaleLogisticsList();
				if (thirdPartyAfterSaleLogistics.getLogisticsCompanyId() != null || thirdPartyAfterSaleLogistics.getLogisticsNo() != null) {
					ThirdPartyReturnLogistics logistics = new ThirdPartyReturnLogistics();
					BeanUtils.copyProperties(thirdPartyAfterSaleLogistics, logistics);
					logistics.setOuterOrderNo(refundBaseInfo.getOuterOrderNo());
					logistics.setOuterRefundNo(refundBaseInfo.getOutRefundNo());
					logistics.setInnerOrderNo(refundBaseInfo.getInnerOrderNo());
					thirdPartyReturnLogisticsRepository.insert(logistics);
				}
				this.insert(thirdPartyAfterSaleHeader);
				thirdPartyAfterSaleItemRepository.batchInsert(partyAfterSaleItems);
				// TODO??????
			}
			else {
				logger.error("???????????????????????????????????????:{}", outRefundNo);
			}
		} catch (Exception e) {
			logger.error("????????????????????????????????????:{}", e.getMessage());
			throw new CommonException("??????????????????????????????");
		} finally {
			rlock.unlock();
		}
		return thirdPartyAfterSaleHeader;
	}

	/**
	 * ?????????????????????
	 * @param id ?????????id
	 * @return
	 */
	@Override
	@ProcessLovValue
	public ThirdPartyAfterSaleHeaderDetailsVO detail(Long id,String code) {
		ThirdPartyAfterSaleHeaderDetailsVO detailsVO = new ThirdPartyAfterSaleHeaderDetailsVO();
		ThirdPartyAfterSaleHeader thirdPartyAfterSaleHeader =null;
		if(!Objects.isNull(id)) {
			 thirdPartyAfterSaleHeader = this.selectByPrimaryKey(id);
		}else {
			thirdPartyAfterSaleHeader = this.queryByOutRefundNo(code);
		}
		if (Objects.isNull(thirdPartyAfterSaleHeader)) {
			throw new CommonException("???????????????????????????");
		}
		detailsVO.setThirdPartyAfterSaleHeader(thirdPartyAfterSaleHeader);
		String outerRefundNo = thirdPartyAfterSaleHeader.getOutRefundNo();
		List<ThirdPartyAfterSaleItem> list = thirdPartyAfterSaleItemRepository.queryByOutRefundNo(outerRefundNo);
		if (CollectionUtils.isNotEmpty(list)) {
			detailsVO.setThirdPartyAfterSaleDetailsVO(list);
		}
		// ?????????????????????
		ThirdPartyAfterReceiverInfo thirdPartyAfterReceiverInfo = thirdPartyAfterReceiverInfoRepository.queryByOuterRefundNo(outerRefundNo);
		if (Objects.nonNull(thirdPartyAfterReceiverInfo)) {
			detailsVO.setThirdPartyAfterReceiverInfo(thirdPartyAfterReceiverInfo);
		}
		// ??????????????????
		ThirdPartyReturnLogistics thirdPartyReturnLogistics = thirdPartyReturnLogisticsRepository.queryByOuterRefundNo(outerRefundNo);
		if (Objects.nonNull(thirdPartyReturnLogistics)) {
			detailsVO.setThirdPartyAfterSaleLogistics(thirdPartyReturnLogistics);
		}
		return detailsVO;
	}

	@Override
	@ProcessLovValue
	public Page<ThirdPartyAfterSalePageVO> orderPage(ThirdPartyAfterSalePageDTO thirdPartyAfterSalePageDTO) {
		return PageHelper.doPage(thirdPartyAfterSalePageDTO.getPage(),thirdPartyAfterSalePageDTO.getSize(),
				()-> thirdPartyAfterSaleHeaderMapper.orderPage(thirdPartyAfterSalePageDTO));
	}

	@Override
	public ThirdPartyAfterSaleHeader queryByOutRefundNo(String outRefundNo) {
		List<ThirdPartyAfterSaleHeader> infos = thirdPartyAfterSaleHeaderMapper.selectByCondition(Condition
				.builder(ThirdPartyAfterSaleHeader.class)
				.andWhere(Sqls.custom()
						.andEqualTo(ThirdPartyAfterSaleHeader.FIELD_OUT_REFUND_NO, outRefundNo))
				.build());
		if (CollUtil.isEmpty(infos)) {
			return null;
		}
		return infos.get(0);
	}

	@Override
	public void createThirdPartyAfterSaleSaga(ThirdPartyAfterSaleDTO thirdPartyAfterSaleDTO) {
		ThirdPartyRefundBaseInfo refundBaseInfo = thirdPartyAfterSaleDTO.getThirdPartyRefundBaseInfo();
		// TODO: 2020/4/20 ???????????????????????????????????????MD5???????????????????????????????????????
		// ???????????????????????????????????????
		ThirdPartyAfterSaleHeader header = queryByOutRefundNo(refundBaseInfo.getOutRefundNo());
		if (Objects.nonNull(header)) {
			logger.error("??????????????????????????????????????????????????????outRefundNo={}", refundBaseInfo.getOutRefundNo());
			throw new CommonException("??????????????????????????????");
		}
		// ????????????saga
		if (CommonConstants.RefundType.REFUND_AND_RETURN_GOODS.equals(refundBaseInfo.getType())) {
			thirdPartyAfterSaleSagaReturnService.createThirdPartyReturnSaga(thirdPartyAfterSaleDTO);
		}else {
			// ????????????saga
			thirdRefundSagaService.createThirdPartyRefundSaga(thirdPartyAfterSaleDTO);
		}
	}
}
