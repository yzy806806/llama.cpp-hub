package org.mark.file.downloader;

/**
 * 	模型下载的请求结构。
 */
public class ModelDownloadRequest {
	private String author;
	private String modelId;
	private String[] downloadUrl;
	private String name;
	private String path;
	private Long size;
	private String lfsOid;
	private Long lfsSize;
	
	public ModelDownloadRequest() {
		
	}
	
	public String getAuthor() {
		return author;
	}
	
	public void setAuthor(String author) {
		this.author = author;
	}
	
	public String getModelId() {
		return modelId;
	}
	
	public void setModelId(String modelId) {
		this.modelId = modelId;
	}
	
	public String[] getDownloadUrl() {
		return downloadUrl;
	}
	
	public void setDownloadUrl(String[] downloadUrl) {
		this.downloadUrl = downloadUrl;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}
	
	public Long getSize() {
		return size;
	}
	
	public void setSize(Long size) {
		this.size = size;
	}
	
	public String getLfsOid() {
		return lfsOid;
	}
	
	public void setLfsOid(String lfsOid) {
		this.lfsOid = lfsOid;
	}
	
	public Long getLfsSize() {
		return lfsSize;
	}
	
	public void setLfsSize(Long lfsSize) {
		this.lfsSize = lfsSize;
	}
}