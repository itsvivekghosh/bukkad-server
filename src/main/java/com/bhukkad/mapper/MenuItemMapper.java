package com.bhukkad.mapper;

import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.storage.MenuImageService;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring")
public abstract class MenuItemMapper {

    @Autowired
    protected MenuImageService menuImageService;

    @Mapping(source = "category.name", target = "categoryName")
    @Mapping(target = "foodType", expression = "java(enumName(menuItem.getFoodType()))")
    @Mapping(target = "spiceLevel", expression = "java(enumName(menuItem.getSpiceLevel()))")
    @Mapping(target = "imageUrl", ignore = true)
    @Mapping(target = "additionalImages", ignore = true)
    @Mapping(target = "customizationOptions", ignore = true)
    public abstract MenuItemResponse toResponse(MenuItem menuItem);

    @AfterMapping
    protected void resolveImageUrls(MenuItem source, @MappingTarget MenuItemResponse target) {
        target.setImageUrl(menuImageService.resolvePublicUrl(source.getImageUrl()));
        if (source.getAdditionalImages() != null) {
            target.setAdditionalImages(source.getAdditionalImages().stream()
                    .map(menuImageService::resolvePublicUrl)
                    .toList());
        }
    }

    protected String enumName(Enum<?> value) {
        return value != null ? value.name() : null;
    }
}
