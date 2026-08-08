import axios from 'axios'; import { Message } from 'element-ui';
const request=axios.create({baseURL:'/api/v1',timeout:120000});
request.interceptors.response.use(r=>{if(r.config.responseType==='blob')return r;const body=r.data;if(body.code!==0){Message.error(body.message||'操作失败');return Promise.reject(new Error(body.message));}return body.data;},e=>{Message.error((e.response&&e.response.data&&e.response.data.message)||e.message||'网络错误');return Promise.reject(e);});export default request;
