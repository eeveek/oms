package org.skyer.goods.domain.service.impl;

import org.skyer.core.domain.Page;
import org.skyer.core.exception.CommonException;
import org.skyer.goods.api.dto.CategoryQueryDTO;
import org.skyer.goods.api.dto.CategorySaveDTO;
import org.skyer.goods.api.dto.CategoryTreeQueryDTO;
import org.skyer.goods.api.dto.CategoryUpdateByStatusFlagDTO;
import org.skyer.goods.domain.entity.Category;
import org.skyer.goods.domain.entity.CategoryAttribute;
import org.skyer.goods.domain.entity.CategoryAttributeValue;
import org.skyer.goods.domain.entity.Spu;
import org.skyer.goods.domain.repository.CategoryAttributeRepository;
import org.skyer.goods.domain.repository.CategoryAttributeValueRepository;
import org.skyer.goods.domain.repository.CategoryRepository;
import org.skyer.goods.domain.repository.SpuRepository;
import org.skyer.goods.domain.service.CategoryManageService;
import org.skyer.goods.domain.vo.CategoryTreeVO;
import org.skyer.goods.domain.vo.CategoryVO;
import org.skyer.goods.infra.convertor.CategoryConvertor;
import org.skyer.goods.infra.util.ListUtils;
import org.skyer.mybatis.domian.Condition;
import org.skyer.mybatis.pagehelper.domain.PageRequest;
import org.skyer.mybatis.util.Sqls;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CategoryManageServiceImpl implements CategoryManageService {

    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private CategoryAttributeRepository categoryAttributeRepository;
    @Autowired
    private CategoryAttributeValueRepository categoryAttributeValueRepository;
    @Autowired
    private SpuRepository spuRepository;

    @Autowired
    private CategoryConvertor categoryConvertor;

    /**
     * ????????????id ????????????????????????
     * @param categoryId ??????id
     */
    public void deleteAttributeAndValue(Long categoryId){
        //??????????????? ????????????????????????
        categoryAttributeValueRepository.deleteByCategoryId(categoryId);

        //????????????
        CategoryAttribute categoryAttribute = new CategoryAttribute();
        categoryAttribute.setCategoryId(categoryId);
        List<CategoryAttribute> categoryAttributeList =
                categoryAttributeRepository.select(CategoryAttribute.FIELD_CATEGORY_ID, categoryId);
        categoryAttributeRepository.batchDelete(categoryAttributeList);

    }

    /**
     * ?????????????????????????????? ??????????????????
     * @param categorySaveDTO ????????????
     */
    @Transactional
    public void save(CategorySaveDTO categorySaveDTO){
        //DTO?????????????????????
        Category category = categoryConvertor.CategorySaveDTOTOCategory(categorySaveDTO);

        // ????????????????????????????????????????????????
        Long pid = category.getParentId();
        if (pid != null && pid != 0L){
            if (pid.equals(categorySaveDTO.getId())){
                // ?????????????????????
                throw new CommonException("?????????????????????");
            }
            Category pCategory = categoryRepository.selectByPrimaryKey(pid);
            if (ObjectUtils.isEmpty(pCategory)){
                // ???????????????????????????????????????
                throw new CommonException("????????????????????????????????????");
            }
            if (pCategory.getSubFlag()) {
                // ?????????????????????????????????
                throw new CommonException("????????????????????????????????????????????????????????????");
            }
            if (!pCategory.getStatusFlag()){
                // ????????????????????????
                throw new CommonException("????????????????????????");
            }

            category.setLevel(pCategory.getLevel() + 1);
        }else{
            category.setLevel(1);
        }

        //?????????????????????id???0
        if (pid == null){
            category.setParentId(0L);
        }
        /* ???????????????????????? */
        //????????????????????????????????????????????????
        List<CategoryAttribute> attributeList = category.getAttributeList();
        if (!ObjectUtils.isEmpty(attributeList)){
            //??????????????????????????????
            List<String> repeatNameList = ListUtils.getRepeatList(
                    attributeList, CategoryAttribute::getName);
            if (!ObjectUtils.isEmpty(repeatNameList)){
                throw new CommonException(
                        MessageFormat.format("???????????????????????????({0})",
                                String.join(",", repeatNameList)));
            }
            //??????????????????????????????
            List<String> repeatCodeList = ListUtils.getRepeatList(attributeList, CategoryAttribute::getCode);
            if (!ObjectUtils.isEmpty(repeatCodeList)){
                throw new CommonException(
                        MessageFormat.format("???????????????????????????({0})",
                                String.join(",", repeatCodeList)));
            }
        }

        //???????????????
        List<String> repeatInfo = attributeList.stream().map(x -> {
            List<CategoryAttributeValue> attributeValueList = x.getAttributeValueList();
            //?????????????????????????????????????????????
            if (x.getFormType() != 3 && ObjectUtils.isEmpty(attributeValueList)) {
                return x.getName() + ":?????????????????????????????????????????????";
            }
            //?????????????????????????????????????????????
            if (x.getFormType() == 3 && !ObjectUtils.isEmpty(attributeValueList)){
                return x.getName() + ":??????????????????????????????";
            }

            //??????????????????
            if (!ObjectUtils.isEmpty(attributeValueList)) {
                //?????????????????????
                List<String> repeatValueList = ListUtils.getRepeatList(
                        attributeValueList, CategoryAttributeValue::getValue);
                if (!ObjectUtils.isEmpty(repeatValueList)) {
                    //????????????????????????????????????
                    return x.getName() + ":???????????????(" + String.join(",", repeatValueList) + ")";
                }
            }
            return null;
        }).filter(Objects::nonNull).collect(Collectors.toList());
        if (!ObjectUtils.isEmpty(repeatInfo)){
            throw new CommonException(
                    MessageFormat.format("????????????????????????({0})",
                            String.join(",", repeatInfo)));
        }

        //????????????
        int nameCount = categoryRepository.selectCountByCondition(Condition.builder(Category.class)
                .andWhere(Sqls.custom()
                        .andEqualTo(Category.FIELD_PARENT_ID, category.getParentId())
                        .andEqualTo(Category.FIELD_NAME, category.getName())
                        .andNotEqualTo(Category.FIELD_ID, category.getId(), true))
                .build());
        if (nameCount > 0){
            throw new CommonException(
                    MessageFormat.format("??????????????????????????????????????????({0})",category.getName()));
        }
        //????????????
        if (!ObjectUtils.isEmpty(category.getCode())) {
            int codeCount = categoryRepository.selectCountByCondition(Condition.builder(Category.class)
                    .andWhere(Sqls.custom()
                            .andEqualTo(Category.FIELD_CODE, category.getCode())
                            .andNotEqualTo(Category.FIELD_ID, category.getId(), true))
                    .build());
            if (codeCount > 0) {
                throw new CommonException("?????????????????????");
            }
        }
        /*??????????????????*/
        if (!ObjectUtils.isEmpty(category.getId())){
            // ?????? ???????????????????????????
            category.setParentId(null);
            //?????? ??????????????????????????????????????????
            if (category.getSubFlag()){
                int subCount = categoryRepository.selectCountByCondition(Condition.builder(Category.class)
                        .andWhere(Sqls.custom().andEqualTo(Category.FIELD_PARENT_ID, category.getId()))
                        .build());
                if (subCount > 0){
                    throw new CommonException("?????????????????????????????????????????????");
                }
            }
            //?????? ?????????????????????????????????????????????
            if (!category.getSubFlag()){
                int spuCount = spuRepository.selectCountByCondition(Condition.builder(Spu.class)
                        .andWhere(Sqls.custom().andEqualTo(Spu.CATEGORY_ID, category.getId()))
                        .build());
                if (spuCount > 0){
                    throw new CommonException("?????????????????????????????????????????????");
                }
            }
        }

        /*????????????*/
        if (ObjectUtils.isEmpty(category.getId())){
            // id???????????????????????????????????????id
            categoryRepository.insertSelective(category);
        }else{
            categoryRepository.updateByPrimaryKeySelective(category);
            //??????????????????????????????
            this.deleteAttributeAndValue(category.getId());
        }

        /*??????????????????*/
        //????????????id
        attributeList.forEach(x->x.setCategoryId(category.getId()));
        //?????????????????????
        List<CategoryAttribute> attributeListNew = categoryAttributeRepository.batchInsertSelective(attributeList);

        /*???????????????*/
        //?????????????????????????????????????????? ??????id
        List<CategoryAttributeValue> valueList = attributeListNew.stream().flatMap(
                x -> {
                    List<CategoryAttributeValue> attributeValueList = x.getAttributeValueList();
                    if (!ObjectUtils.isEmpty(attributeValueList)) {
                        //????????????id
                        return attributeValueList.stream().peek(y -> y.setAttributeId(x.getId()));
                    }
                    return null;
                }
        ).filter(Objects::nonNull).collect(Collectors.toList());

        if (!ObjectUtils.isEmpty(valueList)){
            // ???????????????????????????
            categoryAttributeValueRepository.batchInsertSelective(valueList);
        }
    }

    /**
     * ????????????????????????
     */
    public void updateByStatusFlag(CategoryUpdateByStatusFlagDTO categoryUpdateByStatusFlagDTO){
        Category category = new Category();
        BeanUtils.copyProperties(categoryUpdateByStatusFlagDTO, category);
        categoryRepository.updateByPrimaryKeySelective(category);
    }
    /**
     * ???????????????????????????????????????????????????
     * @param category ????????????
     * @return true?????????
     */
    public boolean hasCategorySub(Category category){
        if (category.getSubFlag()){
            //????????????????????????????????????
            List<Spu> spuList = spuRepository.select(Spu.CATEGORY_ID, category.getId());
            return !ObjectUtils.isEmpty(spuList);
        }else {
            //????????????????????????
            List<Category> categoryList = categoryRepository.select(Category.FIELD_PARENT_ID, category.getId());
            return !ObjectUtils.isEmpty(categoryList);
        }
    }

    /**
     * ??????????????????
     * @param ids ???????????????id??????
     */
    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        ids.forEach(id->{
            Category category = categoryRepository.selectByPrimaryKey(id);
            if (ObjectUtils.isEmpty(category)) return;
            if (hasCategorySub(category)){
                throw new CommonException(MessageFormat.format("??????({0})?????????????????????????????????????????????", category.getName()));
            }
            categoryRepository.deleteByPrimaryKey(category);
            //????????????????????????
            this.deleteAttributeAndValue(category.getId());
        });
    }

    /**
     * ???????????????????????????????????????????????????????????????????????????????????????????????????
     * @param categoryTreeVO ?????????
     * @return ???????????????????????????
     */
    private boolean filterNotSubNode(CategoryTreeVO categoryTreeVO){
        if (categoryTreeVO.getSubFlag()){
            return true;
        }
        List<CategoryTreeVO> children = categoryTreeVO.getChildren();
        if (!ObjectUtils.isEmpty(children)){
            List<CategoryTreeVO> categoryTreeVOList = children.stream().filter(this::filterNotSubNode).collect(Collectors.toList());
            categoryTreeVO.setChildren(categoryTreeVOList);
            return !ObjectUtils.isEmpty(categoryTreeVOList);
        }
        return false;
    }

    @Override
    public List<CategoryTreeVO> getCategoryTree(CategoryTreeQueryDTO queryDTO) {
        CategoryQueryDTO categoryQueryDTO = categoryConvertor.CategoryTreeQueryDTOToCategoryQueryDTO(queryDTO);
        List<Category> categoryList = categoryRepository.selectList(categoryQueryDTO);

        //????????????????????????
        List<CategoryTreeVO> categoryTreeVOList = categoryList.stream().map(x->{
            CategoryTreeVO categoryTreeVO = new CategoryTreeVO();
            BeanUtils.copyProperties(x, categoryTreeVO);
            return categoryTreeVO;
        }).collect(Collectors.toList());

        //?????? ???id
        Map<Long, List<CategoryTreeVO>> groupMap = categoryTreeVOList.stream()
                .collect(Collectors.groupingBy(CategoryTreeVO::getParentId));

        categoryTreeVOList.forEach(x-> x.setChildren(groupMap.get(x.getId())));
        Stream<CategoryTreeVO> categoryTreeVOStream = categoryTreeVOList.stream()
                .filter(x -> x.getParentId() == 0) //??????0????????????
                .peek(x -> x.setParentId(null));//?????????????????? ????????????null

        if (ObjectUtils.nullSafeEquals(true, queryDTO.getRemoveNotSubFlag()) ){
            //???????????????????????????????????????
            categoryTreeVOStream = categoryTreeVOStream.filter(this::filterNotSubNode);
        }

        return categoryTreeVOStream.collect(Collectors.toList());
    }

    /**
     * ?????????????????????????????????
     * @param categoryQueryDTO ????????????????????????
     * @return ????????????
     */
    public Page<CategoryVO> pageIncludedAttr(CategoryQueryDTO categoryQueryDTO){

        PageRequest pageRequest = new PageRequest();
        BeanUtils.copyProperties(categoryQueryDTO, pageRequest);

        categoryQueryDTO.setSort(null); //?????????????????????????????????pageRequest??????sort?????????????????????????????????

        Page<CategoryVO> categoryVOPage = categoryRepository.pageIncludedAttr(pageRequest, categoryQueryDTO);

        //???????????????????????????0???????????????null
        List<CategoryVO> categoryList = categoryVOPage.getContent();
        List<CategoryVO> collect = categoryList.stream().peek(x -> {

            if (x.getParentId() == 0) {
                x.setParentId(null);
            }
        }).collect(Collectors.toList());

        categoryVOPage.setContent(collect);

        return categoryVOPage;
    }
}