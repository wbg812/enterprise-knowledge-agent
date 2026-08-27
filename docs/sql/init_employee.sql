-- ============================================
-- 企业员工花名册 - 建表 + 数据
-- ============================================

-- 建表
CREATE TABLE IF NOT EXISTS employee (
    emp_id      VARCHAR(10)  PRIMARY KEY COMMENT '工号',
    name        VARCHAR(50)  NOT NULL COMMENT '姓名',
    department  VARCHAR(50)  NOT NULL COMMENT '部门',
    position    VARCHAR(50)  NOT NULL COMMENT '职位',
    hire_date   DATE         NOT NULL COMMENT '入职日期',
    education   VARCHAR(20)  NOT NULL COMMENT '学历',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    email       VARCHAR(100) DEFAULT NULL COMMENT '邮箱'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业员工花名册';

-- 插入数据
INSERT INTO employee (emp_id, name, department, position, hire_date, education, phone, email) VALUES
('EMP001', '张伟',   '技术部',     '技术总监',         '2020-03-15', '本科', '13800138001', 'zhangwei@company.com'),
('EMP002', '李娜',   '技术部',     '高级开发工程师',   '2021-06-20', '硕士', '13800138002', 'lina@company.com'),
('EMP003', '王强',   '技术部',     '开发工程师',       '2022-01-10', '本科', '13800138003', 'wangqiang@company.com'),
('EMP004', '刘洋',   '技术部',     '开发工程师',       '2022-08-05', '本科', '13800138004', 'liuyang@company.com'),
('EMP005', '陈静',   '技术部',     '测试工程师',       '2021-11-12', '本科', '13800138005', 'chenjing@company.com'),
('EMP006', '杨帆',   '技术部',     '运维工程师',       '2020-09-18', '本科', '13800138006', 'yangfan@company.com'),
('EMP007', '赵敏',   '市场营销部', '市场总监',         '2019-05-22', '硕士', '13800138007', 'zhaomin@company.com'),
('EMP008', '孙磊',   '市场营销部', '市场经理',         '2020-07-14', '本科', '13800138008', 'sunlei@company.com'),
('EMP009', '周婷',   '市场营销部', '市场专员',         '2021-03-08', '本科', '13800138009', 'zhouting@company.com'),
('EMP010', '吴刚',   '市场营销部', '市场专员',         '2022-04-15', '本科', '13800138010', 'wugang@company.com'),
('EMP011', '郑丽',   '市场营销部', '品牌专员',         '2021-09-20', '本科', '13800138011', 'zhengli@company.com'),
('EMP012', '冯涛',   '市场营销部', '渠道专员',         '2022-06-10', '本科', '13800138012', 'fengtao@company.com'),
('EMP013', '褚芳',   '人事部',     '人事总监',         '2019-08-10', '硕士', '13800138013', 'chufang@company.com'),
('EMP014', '卫平',   '人事部',     '招聘经理',         '2020-10-15', '本科', '13800138014', 'weiping@company.com'),
('EMP015', '蒋雪',   '人事部',     '人事专员',         '2021-12-01', '本科', '13800138015', 'jiangxue@company.com'),
('EMP016', '沈浩',   '财务部',     '财务总监',         '2019-11-05', '硕士', '13800138016', 'shenhao@company.com'),
('EMP017', '韩梅',   '财务部',     '财务经理',         '2020-04-20', '本科', '13800138017', 'hanmei@company.com'),
('EMP018', '唐杰',   '财务部',     '会计',             '2021-07-15', '本科', '13800138018', 'tangjie@company.com'),
('EMP019', '许琳',   '运营部',     '运营总监',         '2020-01-10', '硕士', '13800138019', 'xulin@company.com'),
('EMP020', '邓超',   '运营部',     '运营经理',         '2021-05-18', '本科', '13800138020', 'dengchao@company.com'),
('EMP021', '曹颖',   '运营部',     '运营专员',         '2022-02-14', '本科', '13800138021', 'caoying@company.com'),
('EMP022', '袁斌',   '运营部',     '客服专员',         '2022-09-01', '本科', '13800138022', 'yuanbin@company.com'),
('EMP023', '于洋',   '技术部',     '前端开发工程师',   '2022-10-08', '本科', '13800138023', 'yuyang@company.com'),
('EMP024', '余倩',   '市场营销部', '活动策划',         '2022-11-15', '本科', '13800138024', 'yuqian@company.com'),
('EMP025', '潘峰',   '技术部',     '架构师',           '2020-06-12', '硕士', '13800138025', 'panfeng@company.com');
