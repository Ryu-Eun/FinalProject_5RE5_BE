package com.oreo.finalproject_5re5_be.vc.controller;

import com.oreo.finalproject_5re5_be.global.dto.response.AudioFileInfo;
import com.oreo.finalproject_5re5_be.global.component.AudioInfo;
import com.oreo.finalproject_5re5_be.global.dto.response.ResponseDto;
import com.oreo.finalproject_5re5_be.global.component.S3Service;
import com.oreo.finalproject_5re5_be.vc.dto.request.VcAudioRequest;
import com.oreo.finalproject_5re5_be.vc.dto.request.VcSrcRequest;
import com.oreo.finalproject_5re5_be.vc.dto.request.VcSrcUrlRequest;
import com.oreo.finalproject_5re5_be.vc.dto.request.VcTextRequest;
import com.oreo.finalproject_5re5_be.vc.dto.response.VcActivateResponse;
import com.oreo.finalproject_5re5_be.vc.dto.response.VcResponse;
import com.oreo.finalproject_5re5_be.vc.dto.response.VcTextResponse;
import com.oreo.finalproject_5re5_be.vc.dto.response.VcUrlResponse;
import com.oreo.finalproject_5re5_be.vc.service.VcApiService;
import com.oreo.finalproject_5re5_be.vc.service.VcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.*;

@RestController
@Slf4j
@Validated
@CrossOrigin(origins = "*")
@RequestMapping("/api/vc")
public class VcController {
    private VcService vcService;
    private AudioInfo audioInfo;
    private S3Service s3Service;
    private VcApiService vcApiService;

    @Autowired
    public VcController(VcService vcService,
                        AudioInfo audioInfo,
                        S3Service s3Service,
                        VcApiService vcApiService) {
        this.vcService = vcService;
        this.audioInfo = audioInfo;
        this.s3Service = s3Service;
        this.vcApiService = vcApiService;
    }

    @Operation(
            summary = "SRC 저장",
            description = "프로젝트 seq와 파일을 받아 SRC 파일을 S3와 DB에 저장합니다."
    )
    @PostMapping("/{proSeq}/src")
    public ResponseEntity<ResponseDto<Map<String, List<Object>>>> srcSave(@Valid @Parameter(description = "프로젝트 ID")
                                                                        @PathVariable Long proSeq,
                                                                   @Valid @RequestParam List<MultipartFile> file) {
        List<AudioFileInfo> audioFileInfos = audioInfo.extractAudioFileInfo(file);//배열로 받은 파일 정보 추출
        List<String> upload = s3Service.upload(file, "vc/src");//파일 업로드
        //저장을 위한 파일 정보로 객체 생성
        List<VcSrcRequest> vcSrcRequests = vcService.vcSrcRequestBuilder(audioFileInfos, upload, proSeq);
        List<VcUrlResponse> vcUrlResponses = vcService.srcSave(vcSrcRequests);//객체 저장
        return ResponseEntity.ok()
                .body(new ResponseDto<>(HttpStatus.OK.value(),mapCreate(vcUrlResponses,
                        "src 파일 저장 완료되었습니다.")));
    }



    @Operation(
            summary = "TRG 저장",
            description = "프로젝트 seq 와 파일을 받아 TRG 파일을 S3와 DB에 저장합니다."
    )
    @PostMapping("/{proSeq}/trg")
    public ResponseEntity<ResponseDto<String>>trgSave(@Valid @Parameter(description = "프로젝트 seq") @PathVariable Long proSeq,
                                          @Valid @RequestParam MultipartFile file){
        //들어온 파일을 검사해서 확장자, 길이, 이름, 크기를 추출
        AudioFileInfo info = audioInfo.extractAudioFileInfo(file);
        //파일을 S3에 업로드
        String trgUrl = s3Service.upload(file, "vc/trg");
        //DB에 저장할 객체 생성
        VcAudioRequest trg = vcService.audioRequestBuilder(proSeq, info, trgUrl);
        vcService.trgSave(trg);//저장
        return ResponseEntity.ok()
                .body(new ResponseDto<>(HttpStatus.OK.value(), "TRG 파일 저장이 완료되었습니다."));
    }

    @Operation(
            summary = "Result 파일 저장(VC 생성)",
            description = "src seq 와 파일을 받아 Result 파일을 S3와 DB에 저장합니다."
    )
    @PostMapping("/result")
    public ResponseEntity<ResponseDto<Map<String,Object>>> resultSave(
//            @Valid @Parameter(description = "Src Seq") @PathVariable Long srcSeq,
//                                             @RequestParam("src url")String url,
            @Valid @RequestParam List<VcSrcUrlRequest> vcSrcUrlRequest,
                                             @Valid @RequestParam("trg file") MultipartFile trgFile)  {
        Map<String, Object> map = new HashMap<>();//응답값 생성
        List<MultipartFile> resultFiles = new ArrayList<>();//파일 저장 배열 생성
        String trgId = vcApiService.trgIdCreate(trgFile);//TRG ID 생성
        List<MultipartFile> srcFile = null;
        try {
            srcFile = s3Service.downloadFile(vcSrcUrlRequest);//SRC 파일 다운로드(서버에 저장)
            s3Service.deleteFolder(new File("file"));//SRC 파일 삭제(서버에서 삭제)
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        //결과 파일 생성(VC API)
        List<MultipartFile> resultFile = vcApiService.resultFileCreate(srcFile, trgId);
        //결과 s3 저장
        List<String> resultUrl = s3Service.upload(resultFiles, "vc/result");
        //결과 파일 정보 추출
        List<AudioFileInfo> info = audioInfo.extractAudioFileInfo(resultFile);
        //결과 파일 정보들 가지고 객체 생성
        List<VcAudioRequest> requests = vcService.audioRequestBuilder(vcSrcUrlRequest, info, resultUrl);
        //객체 저장
        List<VcUrlResponse> vcUrlResponses = vcService.resultSave(requests);
        map.put("result", vcUrlResponses);
        map.put("message", "result 파일 저장이 완료되었습니다.");//완료 메시지
        //응답 생성
        return ResponseEntity.ok()
                .body(new ResponseDto<>(HttpStatus.OK.value(), map));
    }

    @Operation(
            summary = "Text 저장",
            description = "src Seq 와 Text 를 받아 DB에 저장 합니다."
    )
    @PostMapping("/src/text")
    public ResponseEntity<ResponseDto<Map<String, List<Object>>>> textSave(@Valid @Parameter(description = "Src Seq")
                                                                               @RequestParam  List<Long> srcSeq,
                                           @Valid @RequestBody List<String> text){
        //객체 생성
        List<VcTextRequest> vcTextRequests = vcService.vcTextResponses(srcSeq, text);
        //저장
        List<VcTextResponse> vcTextResponses = vcService.textSave(vcTextRequests);
        return ResponseEntity.ok()
                .body(new ResponseDto<>(HttpStatus.OK.value(),
                        mapCreate(vcTextResponses,
                        "text 저장 완료되었습니다.")));
    }

    @Operation(
            summary = "src url 호출",
            description = "src Seq 로 파일 url을 가지고 옵니다."
    )
    @GetMapping("/src/url/{srcSeq}")
    public ResponseEntity<ResponseDto<Map<String, Object>>> srcURL(@Valid @PathVariable Long srcSeq){
        //SrcSeq로 URL 정보 추출
        VcUrlResponse srcFile = vcService.getSrcFile(srcSeq);
        Map<String, Object> map = new HashMap<>();//응답값
        map.put("result", srcFile);
        return ResponseEntity.ok()
                .body(new ResponseDto<>(HttpStatus.OK.value(), map));
    }

    @Operation(
            summary = "Result url 호출",
            description = "Result Seq 로 파일 url을 가지고 옵니다."
    )
    @GetMapping("/result/url/{resSeq}")
    public ResponseEntity<ResponseDto<Map<String, Object>>> resultURL(@Valid @PathVariable Long resSeq){
        //Result Seq 로 URL 정보 추출
        VcUrlResponse resultFile = vcService.getResultFile(resSeq);
        Map<String, Object> map = new HashMap<>();//응답갑
        map.put("result", resultFile);
        return ResponseEntity.ok()
                .body(new ResponseDto<>(HttpStatus.OK.value(), map));
    }

    @Operation(
            summary = "프로젝트의 VC 전체 행 조회",
            description = "Result Seq 로 VC 전체 행을 가지고 옵니다."
    )
    @GetMapping("/{proSeq}")
    public ResponseEntity<ResponseDto<Map<String, Object>>> vc(@Valid @PathVariable Long proSeq){
        //Project 의 src, result, text 정보 추출
        List<VcResponse> response = vcService.getVcResponse(proSeq);
        Map<String, Object> map = new HashMap<>();//응답값
        map.put("row", response);
        return ResponseEntity.ok()
                .body(new ResponseDto<>(HttpStatus.OK.value(), map));
    }

    @Operation(
            summary = "SRC 행 삭제",
            description = "SRC 행을 비활성화 상태로 변경합니다. active = 'N' "
    )
    @DeleteMapping("/src")
    public ResponseEntity<ResponseDto<Map<String, List<Object>>>> deleteSrc(@Valid @RequestBody List<Long> srcSeq){
        //삭제 호출
        List<VcActivateResponse> vcActivateResponses = vcService.deleteSrcFile(srcSeq);
        return ResponseEntity.ok()
                .body(new ResponseDto<>(HttpStatus.OK.value(),
                                mapCreate(vcActivateResponses,
                                        "SRC 행 삭제 완료되었습니다.")));
    }

    @Operation(
            summary = "Text 수정",
            description = "text seq 로 text 내용을 변경합니다."
    )
    @PutMapping("/src/{textSeq}")
    public ResponseEntity<ResponseDto<String>> updateText(@Valid @PathVariable Long textSeq,
                                                          @Valid @RequestParam("text") String text){
        vcService.updateText(textSeq, text); //textseq 로 text 값 변경
        return ResponseEntity.ok()
                .body(new ResponseDto<>(HttpStatus.OK.value(), "Text 수정이 완료되었습니다."));
    }

    //중복되는 map<String, List<Object>> url 리턴값 메서드로 변경
    private static Map<String, List<Object>> mapCreate(Object response, String message){
        Map<String, List<Object>> map = new HashMap<>();
        map.put("data", Collections.singletonList(response));//응답 값
        map.put("message", Collections.singletonList(message));//응답 메시지
        return map;
    }
}